package com.t1doo.aiyou.model

/** Only durable independent original edits; never capture input or task proposal forms. */
object RecordEditDraftPackages {
    const val MAX_TEXT_LENGTH = 100_000
    fun validate(drafts: List<OriginalEditDraft>, records: List<RecordPackageOriginal>) {
        require(records.isNotEmpty() && records.all { it.history.isNotEmpty() })
        require(drafts.size <= 20 && drafts.map { it.entryId }.distinct().size == drafts.size &&
            drafts.map { it.id }.distinct().size == drafts.size)
        var units = 0L
        drafts.forEach { draft ->
            RecordPackages.uuid(draft.id)
            val record = records.single { it.sourceId == draft.entryId }
            require(draft.state == OriginalEditDraftState.ACTIVE && draft.version > 0 &&
                draft.baseRevision == record.sourceRevision && draft.sourceRevision in 1..draft.baseRevision &&
                draft.originalText == record.originalText && draft.originalText.length <= MAX_TEXT_LENGTH &&
                draft.text.length <= MAX_TEXT_LENGTH)
            units += (draft.originalText.length.toLong() + draft.text.length) * 2
            require(units <= RecordMediaPackages.MAX_METADATA)
        }
    }
}
