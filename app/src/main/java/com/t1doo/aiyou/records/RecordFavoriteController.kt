package com.t1doo.aiyou.records

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** Optional metadata failure cannot turn an acknowledged record write into a save failure. */
internal class FavoriteSourceReadback(val values: Map<String, RecordFavoriteSnapshot>?)
internal fun readFavoriteSource(store: EntryRepository): FavoriteSourceReadback? {
    val port = store.favorites ?: return null
    return try { FavoriteSourceReadback(port.snapshots()) }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { FavoriteSourceReadback(null) }
}
internal fun LocalRecordUiState.withFavoriteSource(readback: FavoriteSourceReadback?): LocalRecordUiState =
    if (readback == null) this else copy(favorites = readback.values.orEmpty(),
        favoritesAvailable = readback.values != null, favoritesError =
            if (readback.values == null) "收藏状态暂不可读取，记录已保留。请重试读取。" else null)

class RecordFavoriteWrite internal constructor(val token: Long, internal val intent: RecordFavoriteMutation,
    val busy: Boolean = true, val error: String? = null, val conflict: Boolean = false) {
    fun copy(busy: Boolean = this.busy, error: String? = this.error, conflict: Boolean = this.conflict) =
        RecordFavoriteWrite(token, intent, busy, error, conflict)
    override fun toString() = "RecordFavoriteWrite(busy=$busy, redacted)"
}

/** Explicit intents share the actual records queue; private retries never enter SavedState. */
class RecordFavoriteController internal constructor(
    private val current: () -> LocalRecordUiState,
    private val publish: ((LocalRecordUiState) -> LocalRecordUiState) -> Unit,
    private val enqueue: (suspend (EntryRepository) -> Unit) -> Boolean,
) {
    private val tokens = AtomicLong()
    private val reads = AtomicLong()
    @Volatile private var disposed = false
    private fun usable(local: LocalRecordUiState = current()) = !disposed && !local.favoriteControllerRetired &&
        local.ready && !local.opening && local.error == null && local.supportsFavorites
    private fun owns(write: RecordFavoriteWrite, local: LocalRecordUiState = current()) = usable(local) &&
        local.favoriteWrites[write.intent.entryId]?.token == write.token

    fun set(id: String, revision: Int, hash: String, version: Long, favorite: Boolean) {
        val source = current().metadataReadingSource(id, revision)?.takeIf { it.contentHash == hash } ?: return
        set(source, version, favorite)
    }
    fun set(source: RecordMetadataReadingSource, version: Long, favorite: Boolean) {
        val id = source.version.sourceId; val revision = source.version.revision.toInt(); val hash = source.contentHash
        val local = current()
        if (!usable(local) || !local.favoritesAvailable || local.favoritesLoading || local.saving || local.savePending ||
            local.recycleBin != null || local.recordPackage != null || local.captureDiscard != null ||
            local.favoriteWrites[id] != null) return
        val entry = local.entries.firstOrNull { it.id == id && it.currentRevision == revision } ?: return
        if (!source.same(local.metadataReadingSource(id, revision))) return
        val snapshot = local.favorites[id] ?: return
        if (snapshot.version != version || OriginalExports.hash(entry.originalText) != hash || snapshot.isFavorite == favorite) return
        val write = RecordFavoriteWrite(tokens.incrementAndGet(),
            RecordFavoriteMutation(id, revision, hash, version, favorite, UUID.randomUUID().toString(), source.generation))
        publish { latest ->
            if (!usable(latest) || !latest.favoritesAvailable || latest.favoritesLoading || latest.saving || latest.savePending ||
                latest.recycleBin != null || latest.recordPackage != null || latest.captureDiscard != null || latest.favoriteWrites[id] != null ||
                !source.same(latest.metadataReadingSource(id, revision)) || latest.entries.none { it == entry } ||
                latest.favorites[id]?.let { it.version == version && it.isFavorite == snapshot.isFavorite } != true) latest
            else latest.copy(favoriteWrites = latest.favoriteWrites + (id to write), favoriteNotice = null, favoriteNoticeEntryId = null, favoriteNoticeReceipt = null)
        }
        if (owns(write)) submit(write)
    }

    fun retry(id: String) {
        val write = current().favoriteWrites[id]?.takeIf { !it.busy && owns(it) } ?: return
        if (write.conflict) { refresh(); return }
        publish { local -> if (!owns(write, local)) local else local.copy(
            favoriteWrites = local.favoriteWrites + (id to write.copy(busy = true, error = null))) }
        if (owns(write)) submit(write)
    }

    private fun submit(write: RecordFavoriteWrite) {
        if (!enqueue { store ->
            if (!owns(write)) return@enqueue
            try {
                val favorites = checkNotNull(store.favorites)
                val saved = favorites.set(write.intent)
                val entries = store.entries()
                val all = favorites.snapshots()
                val fresh = all[write.intent.entryId] ?: throw RecordFavoriteUnavailable()
                if (fresh.version != saved.version || fresh.isFavorite != saved.isFavorite) throw RecordFavoriteConflict()
                val epoch = store.localSearch?.epoch()
                publish { local ->
                    if (!owns(write, local)) local else local.copy(entries = entries, favorites = all,
                        favoritesAvailable = true, favoritesError = null, localSearchEpoch = epoch,
                        favoriteWrites = local.favoriteWrites - write.intent.entryId,
                        favoriteNotice = if (saved.isFavorite) "已收藏，保存在本机。" else "已取消收藏。",
                        favoriteNoticeEntryId = write.intent.entryId, favoriteNoticeReceipt = saved)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(write, failure is RecordFavoriteConflict || failure is RecordFavoriteUnavailable) }
        }) failed(write, false)
    }

    private fun failed(write: RecordFavoriteWrite, conflict: Boolean) = publish { local ->
        if (!owns(write, local)) local else local.copy(favoritesAvailable = false,
            favoritesError = "收藏状态暂不可确认，请重试读取。", favoriteNotice = null, favoriteNoticeEntryId = null, favoriteNoticeReceipt = null,
            favoriteWrites = local.favoriteWrites + (write.intent.entryId to write.copy(busy = false, conflict = conflict,
                error = if (conflict) "记录或收藏状态已变化，请重新读取后再选择。"
                    else "收藏保存状态待确认，请重试确认这次选择。")))
    }

    fun refresh() {
        if (!usable() || current().favoritesLoading) return
        val generation = reads.incrementAndGet()
        publish { if (!usable(it)) it else it.copy(favoritesLoading = true, favoriteNotice = null, favoriteNoticeEntryId = null, favoriteNoticeReceipt = null) }
        if (!enqueue { store ->
            if (!usable() || reads.get() != generation) return@enqueue
            try {
                val entries = store.entries()
                val all = checkNotNull(store.favorites).snapshots()
                val versions = checkNotNull(store.localProcessing).versions()
                val epoch = store.localSearch?.epoch()
                publish { local -> if (!usable(local) || reads.get() != generation) local else local.copy(
                    entries = entries, favorites = all, favoritesAvailable = true, favoritesLoading = false,
                    localProcessingVersions = versions, localProcessingAvailable = true,
                    favoritesError = null, localSearchEpoch = epoch,
                    // A read alone does not confirm an uncertain intent; keep its stable retry.
                    favoriteWrites = local.favoriteWrites.filterValues { !it.conflict }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                publish { if (!usable(it) || reads.get() != generation) it else it.copy(favoritesAvailable = false,
                    favoritesLoading = false, favoritesError = "收藏状态读取失败，记录已保留。请重试。") }
            }
        }) publish { if (!usable(it)) it else it.copy(favoritesLoading = false,
            favoritesAvailable = false, favoritesError = "收藏状态暂不可读取，请重试。") }
    }

    fun dispose() {
        disposed = true
        publish { it.copy(favoriteControllerRetired = true) }
    }
}
