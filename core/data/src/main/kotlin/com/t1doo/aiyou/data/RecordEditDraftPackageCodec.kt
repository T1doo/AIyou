package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.util.Collections

/** v7 wraps existing authenticated history formats. Text uses exact UTF-16 code units, as the edit store does. */
internal object RecordEditDraftPackageCodec {
    fun encode(preview: RecordMediaPreview, baseVersion: Int, base: ByteArray): ByteArray {
        val drafts = checkNotNull(preview.editDrafts)
        RecordEditDraftPackages.validate(drafts, checkNotNull(preview.history))
        require(baseVersion in 4..6)
        return PackageWriter(RecordMediaPackages.MAX_METADATA).use { out ->
            out.string("AIyou-original-edits-v7", 32); out.i32(7); out.i32(1); out.i32(baseVersion)
            out.i32(base.size); out.raw(base); out.i32(drafts.size)
            drafts.forEach { write(out, it) }; out.bytes()
        }
    }
    fun parse(bytes: ByteArray, parseBase: (ByteArray, Int) -> AuthenticatedRecordPackage): AuthenticatedRecordPackage {
        val input = PackageReader(bytes)
        require(input.string(32) == "AIyou-original-edits-v7" && input.i32() == 7 && input.i32() == 1)
        val version = input.i32(); require(version in 4..6)
        val raw = input.raw(input.i32(), RecordMediaPackages.MAX_METADATA)
        val base = try { parseBase(raw, version) } finally { raw.fill(0) }
        require(base.includesHistory && base.editDrafts == null)
        val drafts = List(input.count(20)) { read(input) }
        input.end(); RecordEditDraftPackages.validate(drafts, base.records)
        return EditPackage(base, drafts, packageHex(packageHash(bytes)))
    }
    fun digest(draft: OriginalEditDraft): String = PackageWriter(402048).use { out ->
        write(out, draft)
        val bytes = out.bytes(); try { packageHex(packageHash(bytes)) } finally { bytes.fill(0) }
    }
    internal fun write(out: PackageWriter, draft: OriginalEditDraft) {
        out.string(draft.id, 36); out.string(draft.entryId, 512); out.i32(draft.baseRevision)
        out.i32(draft.sourceRevision); out.i64(draft.version)
        text(out, draft.originalText); text(out, draft.text)
        out.string(draft.state.name, 16)
    }
    internal fun read(input: PackageReader): OriginalEditDraft = OriginalEditDraft(input.string(36), input.string(512),
        input.i32(), input.i32(), input.i64(), text(input), text(input), OriginalEditDraftState.valueOf(input.string(16)))
    private fun text(out: PackageWriter, value: String) {
        require(value.length <= RecordEditDraftPackages.MAX_TEXT_LENGTH)
        out.i32(value.length)
        val bytes = ByteArray(value.length * 2)
        try { value.forEachIndexed { i, c -> bytes[i*2] = (c.code ushr 8).toByte(); bytes[i*2+1] = c.code.toByte() }; out.raw(bytes) }
        finally { bytes.fill(0) }
    }
    private fun text(input: PackageReader): String {
        val count = input.count(RecordEditDraftPackages.MAX_TEXT_LENGTH)
        val bytes = input.raw(count * 2)
        val chars = CharArray(count)
        return try {
            chars.indices.forEach { i -> chars[i] = (((bytes[i*2].toInt() and 255) shl 8) or (bytes[i*2+1].toInt() and 255)).toChar() }
            String(chars)
        } finally { bytes.fill(0); chars.fill('\u0000') }
    }
    private class EditPackage(private val base: AuthenticatedRecordPackage, drafts: List<OriginalEditDraft>,
        override val digest: String) : AuthenticatedRecordPackage by base {
        override val editDrafts = Collections.unmodifiableList(drafts.toList())
        override fun toString() = "VerifiedOriginalEdits(count=${editDrafts.size}, redacted)"
    }
}
