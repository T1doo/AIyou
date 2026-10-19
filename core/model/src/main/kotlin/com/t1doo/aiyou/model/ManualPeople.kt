package com.t1doo.aiyou.model

import java.util.Collections

/** Explicit record-level names, not inferred identities, contacts or relationships. */
class ManualPeopleSnapshot(val entryId: String, val version: Long, names: List<String>,
    val sourceRevision: Int, val sourceHash: String, val generation: Long,
    aliases: List<ManualPersonAlias> = emptyList()) {
    val names: List<String> = Collections.unmodifiableList(ManualTags.canonical(names))
    val aliases: List<ManualPersonAlias> = Collections.unmodifiableList(ManualPeopleAliases.canonical(this.names, aliases))
    init {
        ManualTags.identifier(entryId); require(version >= 0 && sourceRevision > 0 && generation >= 0 && (version > 0 || names.isEmpty()))
        require(sourceHash.matches(Regex("[0-9a-f]{64}")))
    }
    override fun toString() = "ManualPeopleSnapshot(version=$version, redacted)"
}

class ManualPeopleMutation(val before: ManualPeopleSnapshot, names: List<String>, val mutationId: String,
    aliases: List<ManualPersonAlias> = before.aliases.filter { it.name in ManualTags.canonical(names) }) {
    val names: List<String> = Collections.unmodifiableList(ManualTags.canonical(names))
    val aliases: List<ManualPersonAlias> = Collections.unmodifiableList(ManualPeopleAliases.canonical(this.names, aliases))
    init { ManualTags.identifier(mutationId); require(before.version < Long.MAX_VALUE) }
    override fun toString() = "ManualPeopleMutation(redacted)"
}

/** A user declaration for ONE record, never a contact identity or a transitive relation. */
data class ManualPersonAlias(val alias: String, val name: String) {
    init {
        require(ManualTags.canonical(listOf(alias)) == listOf(alias) && ManualTags.canonical(listOf(name)) == listOf(name))
        require(ManualPeopleAliases.fold(alias) != ManualPeopleAliases.fold(name))
    }
    override fun toString() = "ManualPersonAlias(redacted)"
}

object ManualPeopleAliases {
    const val MAX_COUNT = 12
    fun canonical(names: List<String>, aliases: List<ManualPersonAlias>): List<ManualPersonAlias> {
        require(aliases.size <= MAX_COUNT)
        val named = names.map(::fold).toSet()
        require(aliases.all { it.name in names && fold(it.alias) !in named })
        require(aliases.map { fold(it.alias) }.distinct().size == aliases.size)
        return aliases.toList()
    }
    /** Same simple Unicode folding as the existing literal search; no transliteration or inference. */
    fun fold(value: String): String = buildString(value.length) {
        var at = 0
        while (at < value.length) {
            val point = value.codePointAt(at)
            appendCodePoint(Character.toLowerCase(Character.toUpperCase(point)))
            at += Character.charCount(point)
        }
    }
    fun matches(relation: ManualPersonAlias, foldedTerm: String) =
        fold(relation.alias) == foldedTerm || fold(relation.name) == foldedTerm
}
