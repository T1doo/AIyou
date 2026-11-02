package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.AttachmentIntegrityFailure
import com.t1doo.aiyou.domain.EntryRepository
import com.t1doo.aiyou.domain.LocalSearchRequest
import com.t1doo.aiyou.model.StoredEntry
import com.t1doo.aiyou.model.AttachmentState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Memory-only presentation. Neither attachment bytes nor thumbnails are written to disk. */
class ImageGridTile internal constructor(val picture: AttachmentPicture? = null, val failed: Boolean = false, val noImage:Boolean=false) {
    override fun toString() = "ImageGridTile(redacted)"
}

class ImageGridState internal constructor(internal val owner: OriginalSearchState? = null,
    val tiles: Map<String, ImageGridTile> = emptyMap()) {
    override fun toString() = "ImageGridState(redacted)"
}

/** Uses the existing records IO queue and authenticated AttachmentStore.read path.
 * One queued/in-flight read, no prefetch or retry loop while a tile stays visible.
 * Leaving the visible set evicts its tile; reentry can read it again. No full-result image cache.
 * A request retains at most 12 visible 256px ARGB thumbnails (3 MiB); a decoder may
 * additionally own one 256 KiB unpublished thumbnail. The reused 16 MiB source
 * bound and PNG validator's bounded workspace are separate from the bitmap budget.
 * Published pictures follow Compose/Bitmap lifetime; only unpublished ones are recycled.
 */
class CurrentImageGridController(
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
    private val decode: (ByteArray) -> AttachmentPicture,
    private val current: () -> LocalRecordUiState,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(ImageGridState())
    val state = mutable.asStateFlow()
    private class Request(val search: OriginalSearchState, val allowed: () -> Boolean) {
        var visible = emptyList<String>()
    }
    private var request: Request? = null
    private var working = false
    private var disposed = false

    private fun live(value: Request): Boolean {
        val local = current()
        return !disposed && request === value && value.allowed() && local.ready && !local.opening && local.error == null &&
            !local.saving && !local.savePending && local.entries === value.search.entries &&
            local.localSearch === value.search.owner && local.localSearchEpoch == value.search.indexEpoch
    }

    fun visible(search: OriginalSearchState): ImageGridState = synchronized(lock) {
        val value = request
        if (value != null && value.search === search && live(value)) mutable.value else ImageGridState()
    }

    /** IDs come from LazyColumn.layoutInfo, in result order; offscreen composition never requests IO. */
    fun sync(search: OriginalSearchState, visibleIds: List<String>, allowed: () -> Boolean) = synchronized(lock) {
        if (disposed) return@synchronized
        if (search.stage !in setOf(OriginalSearchStage.READY,OriginalSearchStage.BROWSE) || search.owner == null ||
            (search.stage==OriginalSearchStage.READY && search.proof==null)) {
            clearLocked(); return@synchronized
        }
        var value = request
        if (value?.search !== search) {
            value = Request(search, allowed)
            request = value
            mutable.value = ImageGridState(search)
        }
        if (!live(value)) { clearLocked(); return@synchronized }
        val ids = visibleIds.toSet()
        value.visible = (if(search.stage==OriginalSearchStage.BROWSE) search.entries.orEmpty() else search.hits.map {it.entry}).asSequence().map { it.id }.filter { it in ids }.take(MAX_VISIBLE_IMAGES).toList()
        val kept = mutable.value.tiles.filterKeys { it in value.visible }
        if (kept.size != mutable.value.tiles.size) mutable.value = ImageGridState(search, kept)
        scheduleLocked()
    }

    fun clear(search: OriginalSearchState? = null) = synchronized(lock) {
        if (search == null || request?.search === search) clearLocked()
    }
    fun dispose() = synchronized(lock) { disposed = true; clearLocked() }
    private fun clearLocked() { request = null; mutable.value = ImageGridState() }

    private fun scheduleLocked() {
        val value = request ?: return
        if (working || !live(value)) return
        val id = value.visible.firstOrNull { it !in mutable.value.tiles } ?: return
        val entry = (if(value.search.stage==OriginalSearchStage.BROWSE) value.search.entries.orEmpty() else value.search.hits.map {it.entry}).single { it.id == id }
        working = true
        if (!enqueue { repository -> load(repository, value, entry) }) {
            working = false
            mutable.value = ImageGridState(value.search, mutable.value.tiles + value.visible
                .filter { it !in mutable.value.tiles }.associateWith { ImageGridTile(failed = true) })
        }
    }

    private fun wanted(value: Request, id: String) = synchronized(lock) { live(value) && id in value.visible }
    private suspend fun load(repository: EntryRepository, value: Request, entry: StoredEntry) {
        val id = entry.id
        var picture: AttachmentPicture? = null
        var published = false
        try {
            if (!wanted(value, id)) return
            val owner = checkNotNull(value.search.owner)
            val proof = if(value.search.input.onlyImages) checkNotNull(value.search.proof)
                else owner.candidates(LocalSearchRequest("",onlyImages=true))
            require(proof.epoch==value.search.indexEpoch)
            // Ordinary browsing has no text-search proof. Obtain the same owner's current-original
            // proof; both the record and complete image-membership proof are leased at publication.
            val sourceProof=value.search.proof ?: owner.candidates(LocalSearchRequest(""))
            require(sourceProof.epoch==value.search.indexEpoch)
            if(entry.id !in checkNotNull(proof.currentImageEntryIds)) {
                if(value.search.input.onlyImages) throw AttachmentIntegrityFailure()
                owner.publishCurrent(proof) { owner.resolve(sourceProof,id) { fresh -> synchronized(lock) {
                    if(fresh==entry && live(value) && id in value.visible) {
                        mutable.value=ImageGridState(value.search,mutable.value.tiles+(id to ImageGridTile(noImage=true)))
                        published=true
                    }
                } } }
                return
            }
            var sourceValid = false
            if (!owner.resolve(proof, id) { sourceValid = it == entry && wanted(value, id) } || !sourceValid) return
            if (repository.entries().singleOrNull { it.id == id } != entry || !wanted(value, id)) return
            val store = checkNotNull(repository.attachments)
            val rows = store.list(id, entry.currentRevision)
            require(rows.size <= MAX_ASSOCIATIONS && rows.map { it.intent.id }.distinct().size == rows.size &&
                rows.all { it.intent.entryId == id && it.intent.revision == entry.currentRevision })
            // Stable representative, including a corrupt/failed first image. Never substitute a historical image.
            val item = rows.filter { it.intent.mediaType in LocalAttachmentController.IMAGE_TYPES }.minByOrNull { it.intent.id }
                ?: throw AttachmentIntegrityFailure()
            require(item.state == AttachmentState.READY && item.intent.byteSize in 1..LocalAttachmentController.MAX_SOURCE_BYTES.toLong())
            if (!wanted(value, id)) return
            val bytes = store.read(item.intent.id)
            try {
                if (!wanted(value, id)) return
                require(bytes.size.toLong() == item.intent.byteSize && LocalAttachmentController.hash(bytes) == item.intent.sha256 &&
                    LocalAttachmentController.matchesHeader(bytes, item.intent.mediaType))
                picture = decode(bytes)
            } finally { bytes.fill(0) }
            val decoded = checkNotNull(picture)
            require(decoded.width in 1..MAX_EDGE && decoded.height in 1..MAX_EDGE && decoded.allocationBytes in 1..MAX_TILE_BYTES)
            if (!wanted(value, id) || repository.entries().singleOrNull { it.id == id } != entry ||
                store.list(id, entry.currentRevision) != rows) return
            // Publication stays inside the existing proof's counted SQL lease, including attachment metadata.
            var sourceAccepted=false
            val valid = owner.resolve(proof, id) { imageSource ->
                if(imageSource==entry) sourceAccepted=owner.resolve(sourceProof,id) { fresh -> synchronized(lock) {
                if (fresh == entry && live(value) && id in value.visible) {
                    mutable.value = ImageGridState(value.search, mutable.value.tiles + (id to ImageGridTile(decoded)))
                    published = true
                }
            } } }
            if ((!valid || !sourceAccepted) && published) synchronized(lock) {
                if (request === value) mutable.value = ImageGridState(value.search, mutable.value.tiles - id)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { synchronized(lock) {
            if (live(value) && id in value.visible)
                mutable.value = ImageGridState(value.search, mutable.value.tiles + (id to ImageGridTile(failed = true)))
        } } finally {
            if (!published) picture?.releaseUnpublished()
            synchronized(lock) {
                // A stale storage proof must not start a repeating read of the same tile.
                if (live(value) && id in value.visible && id !in mutable.value.tiles)
                    mutable.value = ImageGridState(value.search, mutable.value.tiles + (id to ImageGridTile(failed = true)))
                working = false; scheduleLocked()
            }
        }
    }

    companion object {
        const val MAX_EDGE = 256
        const val MAX_VISIBLE_IMAGES = 12
        const val MAX_TILE_BYTES = MAX_EDGE.toLong() * MAX_EDGE * 4
        const val MAX_RETAINED_BYTES = MAX_VISIBLE_IMAGES * MAX_TILE_BYTES
        private const val MAX_ASSOCIATIONS = 64
    }
}
