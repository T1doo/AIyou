package com.t1doo.aiyou.domain

import com.t1doo.aiyou.model.*

/** Explicit, bounded plaintext snapshot. A draft remains raw input, never an executable task. */
object OriginalActivityExportCodec {
    const val FORMAT_VERSION = 1
    fun render(snapshot: OriginalExportSnapshot, activities: RecordPackageActivities,
        format: OriginalExportFormat): OriginalExportPayload {
        require(format != OriginalExportFormat.MEDIA_ZIP)
        validate(snapshot, activities)
        val records = snapshot.records.map { OriginalExportCodec.record(it).dropLast(1) + ",\"sourceGeneration\":${it.sourceGeneration}}" }
        val tasks = activities.tasks.map { item -> task(item) }
        val drafts = activities.drafts.map { item -> draft(item) }
        val body = (records + tasks + drafts).joinToString("", transform = { "$it\n" })
        val manifest = "{\"type\":\"manifest\",\"format\":\"AIyou-originals-and-activities\",\"formatVersion\":$FORMAT_VERSION,\"schemaVersion\":1," +
            "\"snapshotId\":${q(snapshot.id)},\"createdAtMillis\":${snapshot.createdAtMillis},\"encrypted\":false," +
            "\"recordCount\":${records.size},\"taskCount\":${tasks.size},\"proposalCount\":${drafts.size}," +
            "\"scope\":[\"currentOriginals\",\"manualTags\",\"allSavedFormalTasks\",\"savedDraftProposals\"]," +
            "\"excluded\":[\"attachments\",\"history\",\"proposalHistory\",\"unsavedForms\",\"aiDerived\",\"reminders\",\"keys\",\"consents\"]," +
            "\"sourceAssociation\":\"exactSelectedCurrentOriginal\",\"hashAlgorithm\":\"SHA-256\"}"
        val end = "{\"type\":\"end\",\"snapshotId\":${q(snapshot.id)},\"recordCount\":${records.size}," +
            "\"taskCount\":${tasks.size},\"proposalCount\":${drafts.size},\"entitiesSha256\":${q(OriginalExports.hash(body))}}"
        val text = when (format) {
            OriginalExportFormat.JSONL -> "$manifest\n$body$end\n"
            OriginalExportFormat.MARKDOWN -> buildString {
                append("# 爱忆原文、正式待办与待确认草稿导出\n\n")
                append("明文开放格式，非完整备份。正式待办与待确认草稿分列；草稿仍是未确认的原始输入，不代表已创建任务或已安排提醒。未保存表单、附件、历史、密钥与联网授权不在范围内。\n\n")
                append(OriginalExportCodec.fenced("json", manifest))
                append("\n## 当前原文与手工标签\n\n")
                snapshot.records.forEachIndexed { i, record ->
                    append(OriginalExportCodec.fenced("json", records[i]))
                    append(OriginalExportCodec.fenced("text", record.entry.originalText)); append('\n')
                }
                append("\n## 正式待办（含未完成、已完成和已取消）\n\n")
                activities.tasks.forEachIndexed { i, item ->
                    append(OriginalExportCodec.fenced("json", tasks[i]))
                    append(OriginalExportCodec.fenced("text", item.task.title)); append('\n')
                }
                append("\n## 已保存待确认草稿（DRAFT，未执行）\n\n")
                activities.drafts.forEachIndexed { i, item ->
                    append(OriginalExportCodec.fenced("json", drafts[i]))
                    append(OriginalExportCodec.fenced("text", item.proposal.titleInput)); append('\n')
                }
                append("\n来源 includedOriginalIndex 为从 0 开始的当前原文序号；null 表示未完整包含，不能据此认定来源可用。所有标题和原始输入均以字面文本保留。\n\n")
                append(OriginalExportCodec.fenced("json", end))
            }
            OriginalExportFormat.MEDIA_ZIP -> error("Open activity export is text only")
        }
        val bytes = OriginalExports.utf8(text)
        try {
            if (bytes.size > OriginalExports.MAX_BYTES) throw OriginalExportTooLarge()
            return OriginalExportPayload(format, bytes)
        } finally { bytes.fill(0) }
    }
    private fun task(item: RecordPackageTask): String {
        val t = item.task
        val schedule = t.schedule?.let { s ->
            val occurrence = ManualTaskDateInput.from(s,t.creationTimeZone).occurrence
            "{\"date\":${q(s.date.toString())},\"time\":${s.time?.toString()?.let(::q) ?: "null"}," +
                "\"timeZone\":${q(s.timeZone)},\"offsetSeconds\":${s.offsetSeconds ?: "null"},\"occurrence\":${occurrence?.name?.let(::q) ?: "null"}}"
        } ?: "null"
        return "{\"type\":\"manualTask\",\"id\":${q(t.id)},\"version\":${t.version},\"title\":${q(t.title)},\"status\":${q(t.status.name)}," +
            times(t.createdAtMillis,t.creationTimeZone,t.updatedAtMillis) + ",\"schedule\":$schedule," +
            "\"source\":${source(t.sourceReceipt?.binding,item.sourceRecord)},\"sourceReceiptHash\":${t.sourceReceipt?.requestHash?.let(::q) ?: "null"}}"
    }
    private fun draft(item: RecordPackageDraft): String {
        val p = item.proposal; val d = p.dateInput
        return "{\"type\":\"taskProposal\",\"id\":${q(p.id)},\"version\":${p.version},\"reviewState\":\"DRAFT\",\"titleInput\":${q(p.titleInput)}," +
            times(p.createdAtMillis,p.creationTimeZone,p.updatedAtMillis) +
            ",\"dateInput\":{\"date\":${q(d.date)},\"time\":${q(d.time)},\"timeZone\":${q(d.timeZone)},\"occurrence\":${d.occurrence?.name?.let(::q) ?: "null"}}," +
            "\"source\":${source(p.binding,item.sourceRecord)}}"
    }
    private fun times(created: Long, zone: String, updated: Long) =
        "\"createdAtMillis\":$created,\"creationTimeZone\":${q(zone)},\"updatedAtMillis\":$updated"
    private fun source(b: TaskSourceBinding?, index: Int): String = if (b == null) "null" else
        "{\"sourceId\":${q(b.source.sourceId)},\"revision\":${b.source.revision},\"contentHash\":${q(b.sourceHash)}," +
            "\"generation\":${b.generation},\"startCodePoint\":${b.start},\"endCodePoint\":${b.end}," +
            "\"includedOriginalIndex\":${if(index < 0) "null" else index.toString()}}"
    private fun validate(s: OriginalExportSnapshot, a: RecordPackageActivities) {
        require(s.records.isNotEmpty() || a.tasks.isNotEmpty() || a.drafts.isNotEmpty())
        require(a.sourceGenerations == s.records.map { it.sourceGeneration })
        fun mapped(index: Int, b: TaskSourceBinding?) {
            if (index < 0) return
            val r = s.records[index]
            require(b != null && r.entry.id == b.source.sourceId && r.entry.currentRevision.toLong() == b.source.revision &&
                r.contentHash == b.sourceHash && r.sourceGeneration == b.generation && b.end <= r.entry.originalText.codePointCount(0,r.entry.originalText.length))
        }
        a.tasks.forEach { mapped(it.sourceRecord,it.task.sourceReceipt?.binding) }
        a.drafts.forEach { mapped(it.sourceRecord,it.proposal.binding) }
    }
    private fun q(value: String) = OriginalExportCodec.quoted(value)
}
