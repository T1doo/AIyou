package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Canonical length-prefixed hashes only; no question, quote, response or credential is logged/stored. */
internal object SelectedQuestionSendProof {
    fun review(value: SelectedQuestionSendReview): String = hash { out ->
        out.text("AIyou selected question send v1"); out.text(value.sessionId); out.text(value.promptVersion)
        out.blob(RemoteTextCodec.encodeConnection(value.connection))
        val r = value.request
        out.text(r.id); out.text(r.selectionId); out.text(r.question); out.writeLong(r.askedAtMillis); out.text(r.questionTimeZone)
        out.writeInt(r.sources.size)
        r.sources.forEachIndexed { index, source ->
            out.reference(source.reference); out.writeLong(source.capturedAtMillis); out.text(source.captureTimeZone); out.text(source.policy.name)
            val expected = value.sources[index]
            out.text(expected.binding.sourceHash); out.writeLong(expected.binding.generation); out.text(expected.policy.name)
            out.blob(expected.connection?.let(RemoteTextCodec::encodeConnection))
            out.blob(expected.consent?.let(RemoteTextCodec::encodeConsent))
        }
    }
    fun reply(request: SelectedQuestionRequest, value: SelectedQuestionReply): String = hash { out ->
        require(value.requestId == request.id && value.selectionId == request.selectionId)
        require(if (value.noEvidence) value.paragraphs.isEmpty() else value.paragraphs.size in 1..5)
        val allowed = request.sources.map { it.reference }.toSet()
        var points = 0; var bytes = 0; var references = 0
        out.text("AIyou selected question result v1"); out.text(value.requestId); out.text(value.selectionId)
        out.writeBoolean(value.noEvidence); out.writeInt(value.paragraphs.size)
        value.paragraphs.forEach { p ->
            require(p.text.isNotBlank() && LocalCandidates.validUnicode(p.text))
            points += p.text.codePointCount(0, p.text.length); bytes += p.text.toByteArray(Charsets.UTF_8).size
            references += p.references.size
            require(p.references.isNotEmpty() && p.references.size <= 5 && p.references.toSet().size == p.references.size &&
                p.references.all { it in allowed } && points <= 1200 && bytes <= 4800 && references <= 5)
            out.text(p.text); out.writeInt(p.references.size); p.references.forEach { out.reference(it) }
        }
    }
    private fun hash(write: (DataOutputStream) -> Unit): String {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).use(write)
        val bytes = buffer.toByteArray()
        return try { RemoteTextCodec.sha256(bytes) } finally { bytes.fill(0) }
    }
    private fun DataOutputStream.text(value: String) {
        require(RemoteTextCodec.validUnicode(value)); blob(value.toByteArray(Charsets.UTF_8))
    }
    private fun DataOutputStream.blob(value: ByteArray?) {
        if (value == null) writeInt(-1) else try { writeInt(value.size); write(value) } finally { value.fill(0) }
    }
    private fun DataOutputStream.reference(value: OriginalTextReference) {
        text(value.source.sourceId); writeLong(value.source.revision); writeInt(value.start); writeInt(value.end); text(value.quote)
    }
}
