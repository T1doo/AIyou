package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.io.*
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import javax.crypto.Cipher

/** v2 flat authenticated frames, not an archive: no names are ever interpreted as filesystem paths.
 * Each image is independently bounded (16 MiB), with a 64 MiB cumulative bound. Metadata is <=2 MiB+32 KiB.
 * Frame nonce = random 8-byte prefix + checked sequence; AAD binds header, metadata hash and frame index.
 * An authenticated terminal frame and exact EOF prevent a valid prefix from becoming a valid package.
 */
internal class RecordMediaPackageCipher(private val random: SecureRandom = SecureRandom(), private val beforeKdf: () -> Unit = {}) {
    private val legacy = JcaRecordPackageCipher()
    suspend fun seal(preview: RecordMediaPreview, password: CharArray,
        read: suspend (RecordPackageImage) -> ByteArray, current: suspend () -> Boolean): PreparedRecordMediaPackage {
        var key: ByteArray? = null
        var ownedMetadata:ByteArray?=null
        try {
            RecordPackages.password(password)
            val metadata = metadata(preview).also {ownedMetadata=it}
            val salt = ByteArray(16).also(random::nextBytes)
            val prefix = ByteArray(8).also(random::nextBytes)
            val version=version(preview)
            val header = ByteBuffer.allocate(40).put(when(version) { 2 -> MAGIC; 3 -> MAGIC3; 4 -> MAGIC4; 5 -> MAGIC5; else -> MAGIC6 }).putInt(version).putInt(RecordPackages.ITERATIONS).put(salt).put(prefix).array()
            key = RecordPackageCrypto.derive(password, salt,beforeKdf)
            return Prepared(header, metadata, checkNotNull(key), preview.images, read, current).also { key = null;ownedMetadata=null }
        } catch (_: Exception) { throw RecordPackageInvalid() }
        finally { key?.fill(0); ownedMetadata?.fill(0);password.fill('\u0000') }
    }

    suspend fun open(source: () -> InputStream, password: CharArray): VerifiedRecordPackage {
        var key: ByteArray? = null
        try {
            RecordPackages.password(password)
            val opened=source().use { input ->
                val magic = exact(input, 8)
                if (!magic.contentEquals(MAGIC) && !magic.contentEquals(MAGIC3) && !magic.contentEquals(MAGIC4) && !magic.contentEquals(MAGIC5) && !magic.contentEquals(MAGIC6)) {
                    // v1 remains bounded at its original 2 MiB and uses its unchanged parser/KDF.
                    val buffer = ByteArray(RecordPackages.MAX_ENVELOPE + 1)
                    magic.copyInto(buffer); var at = 8
                    try {
                        while (at < buffer.size) { val n = input.read(buffer, at, buffer.size-at); if (n < 0) break; require(n > 0); at += n }
                        require(at <= RecordPackages.MAX_ENVELOPE)
                        val bytes = buffer.copyOf(at)
                        return try { legacy.open(bytes, password) } finally { bytes.fill(0) }
                    } finally { buffer.fill(0) }
                }
                val header = magic + exact(input, 32)
                val fields = ByteBuffer.wrap(header, 8, 32)
                require(fields.int == (if(magic.contentEquals(MAGIC)) 2 else if(magic.contentEquals(MAGIC3)) 3 else if(magic.contentEquals(MAGIC4)) 4 else if(magic.contentEquals(MAGIC5)) 5 else 6) && fields.int == RecordPackages.ITERATIONS)
                val salt = ByteArray(16).also(fields::get)
                val firstLength = exact(input,4)
                require(ByteBuffer.wrap(firstLength).int in 16..RecordMediaPackages.MAX_METADATA+16)
                key = RecordPackageCrypto.derive(password, salt,beforeKdf)
                val parsed = scan(SequenceInputStream(firstLength.inputStream(),input), header, checkNotNull(key), null, null)
                AuthenticatedMediaPackage(parsed.first, parsed.second, parsed.third, header, checkNotNull(key), source, this)
            }
            return opened.also {key=null}
        } catch (_: Exception) { throw RecordPackageInvalid() }
        finally { key?.fill(0); password.fill('\u0000') }
    }

    /** Re-authenticate the selected document on every import; substitution/truncation cannot commit a prefix. */
    internal suspend fun consume(pack: AuthenticatedMediaPackage, each: suspend (Int, ByteArray) -> Unit) {
        pack.withKey { key ->
            pack.source().use { input ->
                require(MessageDigest.isEqual(exact(input, 40), pack.header))
                val parsed = scan(input, pack.header, key, pack.digest, each)
                require(parsed.second == pack.images)
            }
        }
    }

    private suspend fun scan(input: InputStream, header: ByteArray, key: ByteArray, expected: String?,
        each: (suspend (Int, ByteArray) -> Unit)?): Triple<AuthenticatedRecordPackage, List<RecordPackageImage>, String> {
        val metadata = frameRead(input, header, key, 0, ZERO, RecordMediaPackages.MAX_METADATA)
        val binding = packageHash(metadata)
        try {
            val version = ByteBuffer.wrap(header,8,4).int
            val original = parseMetadata(metadata, version)
            val images = original.images
            val digest = packageHex(binding)
            if (expected != null) require(expected == digest)
            for ((index, image) in images.withIndex()) {
                val bytes = frameRead(input, header, key, index+1, binding, image.byteSize.toInt())
                try { checkImage(image, bytes); each?.invoke(index, bytes) } finally { bytes.fill(0) }
            }
            val terminal = frameRead(input, header, key, images.size+1, binding, 32)
            try { require(MessageDigest.isEqual(terminal, binding) && input.read() == -1) } finally { terminal.fill(0) }
            return Triple(original, images, digest)
        } finally { metadata.fill(0) }
    }

    private fun version(preview: RecordMediaPreview) = if (preview.snapshot.records.any { it.people.version > 0 }) 6
        else if (preview.snapshot.records.any { it.eventDate.date != null }) 5 else if (preview.history != null) 4
        else if (preview.activities == null) 2 else 3
    private fun parseMetadata(metadata: ByteArray, version: Int): AuthenticatedRecordPackage =
        if (version == 6) RecordPeoplePackageCodec.parse(metadata, ::parseMetadata)
        else if (version == 5) RecordEventDatePackageCodec.parse(metadata, ::parseMetadata)
        else if (version == 4) RecordHistoryPackageCodec.parse(metadata) else {
            require(version in 2..3); parseCurrentMetadata(metadata, version == 3)
        }
    private fun metadata(preview: RecordMediaPreview): ByteArray {
        if (preview.snapshot.records.any { it.people.version > 0 }) {
            val legacy = RecordPeoplePackageCodec.legacyPreview(preview)
            val base = metadata(legacy)
            return try { RecordPeoplePackageCodec.encode(preview, version(legacy), base) } finally { base.fill(0) }
        }
        if (preview.snapshot.records.any { it.eventDate.date != null }) {
            val baseVersion = if (preview.history != null) 4 else if (preview.activities != null) 3 else 2
            val base = metadata(RecordEventDatePackageCodec.legacyPreview(preview))
            return try { RecordEventDatePackageCodec.encode(preview, baseVersion, base) } finally { base.fill(0) }
        }
        if(preview.history!=null) return RecordHistoryPackageCodec.encode(preview)
        val v3=preview.activities!=null
        val original = legacy.encode(preview.snapshot,true,v3)
        return try { PackageWriter(RecordMediaPackages.MAX_METADATA).use { out ->
            out.string(if(v3) "AIyou-activities-v3" else "AIyou-images-v2", 32); out.i32(if(v3) 3 else 2); out.i32(1)
            out.strings(if(v3) SCOPE3 else SCOPE,5,32); out.strings(if(v3) EXCLUDED3 else EXCLUDED,8,32); out.i32(original.size); out.raw(original); out.i32(preview.images.size)
            preview.images.forEach { i -> out.string(i.sourceId,512); out.i32(i.sourceRevision); out.string(i.attachmentId,36)
                out.string(i.mediaType,32); out.i64(i.byteSize); out.raw(packageUnhex(i.sha256)) }
            preview.activities?.let {a ->
                val decoded=legacy.parse(original,true,true); RecordPackageActivityCodec.validate(a,decoded.records)
                val raw=RecordPackageActivityCodec.encode(a)
                try {out.i32(raw.size);out.raw(raw)} finally {raw.fill(0)}
            }
            out.bytes()
        } } finally { original.fill(0) }
    }
    private fun parseCurrentMetadata(metadata:ByteArray,v3:Boolean):AuthenticatedRecordPackage {
        val reader=PackageReader(metadata)
        require(reader.string(32) == (if(v3) "AIyou-activities-v3" else "AIyou-images-v2") && reader.i32() == (if(v3) 3 else 2) && reader.i32() == 1)
        require(reader.strings(5,32) == (if(v3) SCOPE3 else SCOPE) && reader.strings(8,32) == (if(v3) EXCLUDED3 else EXCLUDED))
        val originalBytes=reader.raw(reader.i32(),RecordPackages.MAX_PLAINTEXT)
        val original=try {legacy.parse(originalBytes,true,v3)} finally {originalBytes.fill(0)}
        val images=List(reader.count(RecordMediaPackages.MAX_IMAGES)) {
            RecordPackageImage(reader.string(512),reader.i32(),reader.string(36),reader.string(32),reader.i64(),packageHex(reader.raw(32)))
        }
        val activities=if(v3) {
            val raw=reader.raw(reader.i32(),RecordMediaPackages.MAX_METADATA)
            try {RecordPackageActivityCodec.decode(raw).also {RecordPackageActivityCodec.validate(it,original.records)}} finally {raw.fill(0)}
        } else null
        reader.end();validate(original,images)
        return CurrentMediaOriginal(original,images,activities)
    }
    private fun validate(original: AuthenticatedRecordPackage, images: List<RecordPackageImage>) {
        require(images.map { it.attachmentId }.distinct().size == images.size)
        require(images.sumOf { it.byteSize } <= RecordMediaPackages.MAX_IMAGE_TOTAL)
        images.forEach { i -> require(original.records.any { it.sourceId == i.sourceId && it.sourceRevision == i.sourceRevision }) }
        original.records.filter { it.originalText.isBlank() }.forEach { r ->
            require(images.any { it.sourceId==r.sourceId && it.sourceRevision==r.sourceRevision })
        }
    }
    private inner class Prepared(private val header: ByteArray, private val metadata: ByteArray, private val key: ByteArray,
        private val images: List<RecordPackageImage>, private val read: suspend (RecordPackageImage) -> ByteArray,
        private val current: suspend () -> Boolean) : PreparedRecordMediaPackage {
        private var used = false
        private var released = false
        override suspend fun writeTo(output: OutputStream, active: () -> Boolean) {
            synchronized(this) { check(!used && !released); used = true }
            try {
                check(active() && current()); output.write(header)
                val binding = packageHash(metadata)
                frameWrite(output, header, key, 0, ZERO, metadata)
                images.forEachIndexed { index, image ->
                    check(active()); val bytes = read(image)
                    try { checkImage(image, bytes); frameWrite(output, header, key, index+1, binding, bytes) } finally { bytes.fill(0) }
                }
                check(active() && current())
                frameWrite(output, header, key, images.size+1, binding, binding); output.flush()
            } finally { release() }
        }
        @Synchronized override fun release() { released = true; key.fill(0); metadata.fill(0) }
    }
    companion object {
        private val MAGIC = "AIYOUPK2".toByteArray(Charsets.US_ASCII)
        private val MAGIC3 = "AIYOUPK3".toByteArray(Charsets.US_ASCII)
        private val MAGIC4 = "AIYOUPK4".toByteArray(Charsets.US_ASCII)
        private val MAGIC5 = "AIYOUPK5".toByteArray(Charsets.US_ASCII)
        private val MAGIC6 = "AIYOUPK6".toByteArray(Charsets.US_ASCII)
        private val ZERO = ByteArray(32)
        private val SCOPE = listOf("currentOriginals","manualTags","readyOriginalImages")
        private val EXCLUDED = listOf("originalHistory","tasks","drafts","aiDerived","serviceKeys","deviceKeys")
        private val SCOPE3=SCOPE+listOf("manualTasks","taskProposals")
        private val EXCLUDED3=listOf("originalHistory","captureDrafts","editDrafts","aiDerived","serviceKeys","deviceKeys","reminders","consents")
        private fun crypt(mode: Int, header: ByteArray, key: ByteArray, index: Int, binding: ByteArray, input: ByteArray): ByteArray {
            val nonce = header.copyOfRange(32,40) + ByteBuffer.allocate(4).putInt(index).array()
            val aad = header + binding + ByteBuffer.allocate(4).putInt(index).array()
            return RecordPackageCrypto.crypt(mode,key,nonce,aad,input)
        }

        private fun frameWrite(out: OutputStream, header: ByteArray, key: ByteArray, index: Int, binding: ByteArray, bytes: ByteArray) {
            val encrypted = crypt(Cipher.ENCRYPT_MODE,header,key,index,binding,bytes)
            try { out.write(ByteBuffer.allocate(4).putInt(encrypted.size).array()); out.write(encrypted) } finally { encrypted.fill(0) }
        }
        private fun frameRead(input: InputStream, header: ByteArray, key: ByteArray, index: Int, binding: ByteArray, max: Int): ByteArray {
            val size = ByteBuffer.wrap(exact(input,4)).int
            require(size in 16..max+16) // BEFORE allocation; cumulative metadata/image count bounds were checked.
            val encrypted = exact(input,size)
            return try { crypt(Cipher.DECRYPT_MODE,header,key,index,binding,encrypted) } finally { encrypted.fill(0) }
        }
        private fun exact(input: InputStream, size: Int): ByteArray {
            val bytes = ByteArray(size)
            try { var at = 0; while (at < size) { val n = input.read(bytes,at,size-at); require(n > 0); at += n }; return bytes }
            catch (e: Exception) { bytes.fill(0); throw e }
        }
        internal fun checkImage(image: RecordPackageImage, bytes: ByteArray) {
            require(bytes.size.toLong() == image.byteSize && packageHex(packageHash(bytes)) == image.sha256)
            // MIME is allowlisted and must also agree with the original file signature.
            require(if (image.mediaType == "image/png") bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))
                else bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte())
        }
    }
}

private class CurrentMediaOriginal(private val original:AuthenticatedRecordPackage, images:List<RecordPackageImage>,
    override val activities:RecordPackageActivities?): AuthenticatedRecordPackage by original {
    override val images=Collections.unmodifiableList(images.toList())
}

internal class AuthenticatedMediaPackage(private val original: AuthenticatedRecordPackage,
    images: List<RecordPackageImage>, override val digest: String, internal val header: ByteArray,
    private val key: ByteArray, internal val source: () -> InputStream, internal val cipher: RecordMediaPackageCipher) : AuthenticatedRecordPackage {
    override val manifest get() = original.manifest
    override val records get() = original.records
    override val recordDigests get() = original.recordDigests
    override val activities get() = original.activities
    override val includesHistory get() = original.includesHistory
    override val images = Collections.unmodifiableList(images.toList())
    private var released = false
    internal suspend fun <T> withKey(block: suspend (ByteArray) -> T): T {
        val borrowed = synchronized(this) { check(!released); key.copyOf() }
        return try { block(borrowed) } finally { borrowed.fill(0) }
    }
    @Synchronized override fun release() { released = true; key.fill(0) }
    override fun toString() = "AuthenticatedMediaPackage(images=${images.size}, redacted)"
}
