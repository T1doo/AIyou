package com.t1doo.aiyou.model

/** A user choice attached to a saved record identity, independent of text revisions and tags. */
class RecordFavoriteSnapshot(val entryId: String, val version: Long, val isFavorite: Boolean) {
    init {
        RecordFavorites.identifier(entryId)
        require(version >= 0 && (version > 0 || !isFavorite)) { "Invalid favorite version" }
    }
    override fun toString() = "RecordFavoriteSnapshot(version=$version, redacted)"
}

/** Explicit target value: replaying this intent never toggles the acknowledged state again. */
class RecordFavoriteMutation(val entryId: String, val sourceRevision: Int, val sourceHash: String,
    val expectedVersion: Long, val isFavorite: Boolean, val mutationId: String) {
    init {
        RecordFavorites.identifier(entryId); RecordFavorites.identifier(mutationId)
        require(sourceRevision > 0 && expectedVersion in 0 until Long.MAX_VALUE) { "Invalid favorite intent" }
        require(Regex("[0-9a-f]{64}").matches(sourceHash)) { "Invalid favorite source" }
    }
    override fun toString() = "RecordFavoriteMutation(redacted)"
}

object RecordFavorites {
    fun identifier(value: String) {
        require(value.isNotBlank() && value.length <= 128 && value.none { Character.isISOControl(it) }) {
            "Invalid favorite identity"
        }
        var at = 0
        while (at < value.length) {
            val ch = value[at++]
            if (Character.isHighSurrogate(ch)) {
                require(at < value.length && Character.isLowSurrogate(value[at++])) { "Invalid favorite identity" }
            } else require(!Character.isLowSurrogate(ch)) { "Invalid favorite identity" }
        }
    }
}
