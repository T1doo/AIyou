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
    /** v8 borrows the existing KDF, nonce/AAD framing, terminal and streaming image verification.
     * It has a separate logical manifest and bounds; v1–v7 parsers never accept it. */
    internal fun sealFull(data: FullBackupData, password: CharArray,
        read: suspend (RecordPackageAttachment) -> ByteArray, current: suspend () -> Boolean): PreparedRecordMediaPackage {
        var key:ByteArray?=null;var metadata:ByteArray?=null
        try {
            RecordPackages.password(password)
            metadata=FullBackupCodec.encode(data)
            val version=data.version
            val salt=ByteArray(16).also(random::nextBytes);val prefix=ByteArray(8).also(random::nextBytes)
            val header=ByteBuffer.allocate(40).put(when(version) { 8 -> MAGIC8;9 -> MAGICF9;10 -> MAGICF10;11 -> MAGICF11;12 -> MAGICF12;13 -> MAGICF13;else -> error("Unknown full version") }).putInt(version).putInt(RecordPackages.ITERATIONS).put(salt).put(prefix).array()
            key=RecordPackageCrypto.derive(password,salt,beforeKdf)
            return Prepared(header,metadata,checkNotNull(key),data.media,read,current).also { key=null;metadata=null }
        } finally { key?.fill(0);metadata?.fill(0);password.fill('\u0000') }
    }
    internal suspend fun openFull(source:()->InputStream,password:CharArray): AuthenticatedFullBackup {
        var key:ByteArray?=null;var data:FullBackupData?=null
        try {
            RecordPackages.password(password)
            return source().use { input ->
                val header=exact(input,40);val magic=header.copyOfRange(0,8);require(magic.contentEquals(MAGIC8) || magic.contentEquals(MAGICF9) || magic.contentEquals(MAGICF10) || magic.contentEquals(MAGICF11) || magic.contentEquals(MAGICF12) || magic.contentEquals(MAGICF13))
                val fields=ByteBuffer.wrap(header,8,32)
                require(fields.int==(if(magic.contentEquals(MAGIC8)) 8 else if(magic.contentEquals(MAGICF9)) 9 else if(magic.contentEquals(MAGICF10)) 10 else if(magic.contentEquals(MAGICF11)) 11 else if(magic.contentEquals(MAGICF12)) 12 else 13) && fields.int==RecordPackages.ITERATIONS)
                val salt=ByteArray(16).also(fields::get)
                val first=exact(input,4);require(ByteBuffer.wrap(first).int in 16..FullBackupCodec.MAX_METADATA+16)
                key=RecordPackageCrypto.derive(password,salt,beforeKdf)
                val parsed=scanFull(SequenceInputStream(first.inputStream(),input),header,checkNotNull(key),null,null)
                data=parsed.first
                AuthenticatedFullBackup(parsed.first,parsed.second,header,checkNotNull(key),source,this).also { key=null;data=null }
            }
        } catch(c: java.util.concurrent.CancellationException) { throw c }
        catch(_:Exception) { throw RecordPackageInvalid() }
        finally { key?.fill(0);data?.release();password.fill('\u0000') }
    }
    internal suspend fun consumeFull(pack:AuthenticatedFullBackup, each:suspend (Int,ByteArray)->Unit) {
        pack.withKey { key -> pack.source().use { input ->
            require(MessageDigest.isEqual(exact(input,40),pack.header))
            val parsed=scanFull(input,pack.header,key,pack.digest,each)
            try { require(parsed.first.media==pack.data.media) } finally { parsed.first.release() }
        } }
    }
    private suspend fun scanFull(input:InputStream,header:ByteArray,key:ByteArray,expected:String?,
        each:(suspend (Int,ByteArray)->Unit)?):Pair<FullBackupData,String> {
        val metadata=frameRead(input,header,key,0,ZERO,FullBackupCodec.MAX_METADATA)
        var data:FullBackupData?=null
        try {
            val binding=packageHash(metadata);val digest=packageHex(binding)
            require(expected==null || expected==digest)
            val parsed=FullBackupCodec.decode(metadata).also { data=it }
            require(parsed.version==ByteBuffer.wrap(header,8,4).int)
            parsed.media.forEachIndexed { index,image ->
                val bytes=frameRead(input,header,key,index+1,binding,image.byteSize.toInt())
                try { checkImage(image,bytes);each?.invoke(index,bytes) } finally { bytes.fill(0) }
            }
            val terminal=frameRead(input,header,key,parsed.media.size+1,binding,32)
            try { require(MessageDigest.isEqual(terminal,binding) && input.read()==-1) } finally { terminal.fill(0) }
            return (parsed to digest).also { data=null }
        } finally { data?.release();metadata.fill(0) }
    }
    suspend fun seal(preview: RecordMediaPreview, password: CharArray,
        read: suspend (RecordPackageAttachment) -> ByteArray, current: suspend () -> Boolean): PreparedRecordMediaPackage {
        var key: ByteArray? = null
        var ownedMetadata:ByteArray?=null
        try {
            RecordPackages.password(password)
            val metadata = metadata(preview).also {ownedMetadata=it}
            val salt = ByteArray(16).also(random::nextBytes)
            val prefix = ByteArray(8).also(random::nextBytes)
            val version=version(preview)
            val header = ByteBuffer.allocate(40).put(when(version) { 2 -> MAGIC; 3 -> MAGIC3; 4 -> MAGIC4; 5 -> MAGIC5; 6 -> MAGIC6; 9 -> MAGIC9; else -> MAGIC7 }).putInt(version).putInt(RecordPackages.ITERATIONS).put(salt).put(prefix).array()
            key = RecordPackageCrypto.derive(password, salt,beforeKdf)
            return Prepared(header, metadata, checkNotNull(key), preview.media, read, current).also { key = null;ownedMetadata=null }
        } catch (_: Exception) { throw RecordPackageInvalid() }
        finally { key?.fill(0); ownedMetadata?.fill(0);password.fill('\u0000') }
    }

    suspend fun open(source: () -> InputStream, password: CharArray): VerifiedRecordPackage {
        var key: ByteArray? = null
        try {
            RecordPackages.password(password)
            val opened=source().use { input ->
                val magic = exact(input, 8)
                if (!magic.contentEquals(MAGIC) && !magic.contentEquals(MAGIC3) && !magic.contentEquals(MAGIC4) && !magic.contentEquals(MAGIC5) && !magic.contentEquals(MAGIC6) && !magic.contentEquals(MAGIC7) && !magic.contentEquals(MAGIC9)) {
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
                require(fields.int == (if(magic.contentEquals(MAGIC)) 2 else if(magic.contentEquals(MAGIC3)) 3 else if(magic.contentEquals(MAGIC4)) 4 else if(magic.contentEquals(MAGIC5)) 5 else if(magic.contentEquals(MAGIC6)) 6 else if(magic.contentEquals(MAGIC9)) 9 else 7) && fields.int == RecordPackages.ITERATIONS)
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
                require(parsed.second == pack.media)
            }
        }
    }

    private suspend fun scan(input: InputStream, header: ByteArray, key: ByteArray, expected: String?,
        each: (suspend (Int, ByteArray) -> Unit)?): Triple<AuthenticatedRecordPackage, List<RecordPackageAttachment>, String> {
        val metadata = frameRead(input, header, key, 0, ZERO, RecordMediaPackages.MAX_METADATA)
        val binding = packageHash(metadata)
        try {
            val version = ByteBuffer.wrap(header,8,4).int
            val original = parseMetadata(metadata, version)
            val images = original.media
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

    private fun version(preview:RecordMediaPreview)=if(preview.audios.isNotEmpty()) 9 else legacyVersion(preview)
    private fun legacyVersion(preview: RecordMediaPreview) = if (preview.editDrafts != null) 7 else if (preview.snapshot.records.any { it.people.version > 0 }) 6
        else if (preview.snapshot.records.any { it.eventDate.date != null }) 5 else if (preview.history != null) 4
        else if (preview.activities == null) 2 else 3
    private fun parseMetadata(metadata:ByteArray,version:Int):AuthenticatedRecordPackage {
        if(version!=9)return parseLegacy(metadata,version,emptyList())
        val r=PackageReader(metadata);require(r.string(32)=="AIyou-audio-media-v9" && r.i32()==9 && r.i32()==1)
        val audios=List(r.count(RecordMediaPackages.MAX_IMAGES)) {
            RecordPackageAudio(r.string(512),r.i32(),r.string(36),r.string(32),r.i64(),packageHex(r.raw(32)))
        }
        require(audios.isNotEmpty())
        val baseVersion=r.i32();require(baseVersion in 2..7)
        val base=r.raw(r.i32(),RecordMediaPackages.MAX_METADATA);r.end()
        return try {
            parseLegacy(base,baseVersion,audios).also { original ->
                require(original.media.size<=RecordMediaPackages.MAX_IMAGES && original.media.sumOf { it.byteSize }<=RecordMediaPackages.MAX_IMAGE_TOTAL)
                require(original.media.map { it.attachmentId }.distinct().size==original.media.size)
            }
        } finally { base.fill(0) }
    }
    private fun parseLegacy(metadata:ByteArray,version:Int,audios:List<RecordPackageAudio>):AuthenticatedRecordPackage {
        val original=when(version) {
            7 -> RecordEditDraftPackageCodec.parse(metadata) { bytes,v -> parseLegacy(bytes,v,audios) }
            6 -> RecordPeoplePackageCodec.parse(metadata) { bytes,v -> parseLegacy(bytes,v,audios) }
            5 -> RecordEventDatePackageCodec.parse(metadata) { bytes,v -> parseLegacy(bytes,v,audios) }
            4 -> RecordHistoryPackageCodec.parse(metadata,audios)
            else -> { require(version in 2..3);parseCurrentMetadata(metadata,version==3,audios) }
        }
        return if(audios.isEmpty())original else AudioEvidence(original,audios)
    }
    private fun metadata(preview:RecordMediaPreview):ByteArray {
        if(preview.audios.isEmpty())return legacyMetadata(preview)
        val base=legacyMetadata(preview)
        return try { PackageWriter(RecordMediaPackages.MAX_METADATA).use { w ->
            w.string("AIyou-audio-media-v9",32);w.i32(9);w.i32(1);w.i32(preview.audios.size)
            preview.audios.forEach { a -> w.string(a.sourceId,512);w.i32(a.sourceRevision);w.string(a.attachmentId,36);w.string(a.mediaType,32);w.i64(a.byteSize);w.raw(packageUnhex(a.sha256)) }
            w.i32(legacyVersion(preview));w.i32(base.size);w.raw(base);w.bytes()
        } } finally { base.fill(0) }
    }
    private fun legacyMetadata(preview: RecordMediaPreview): ByteArray {
        if (preview.editDrafts != null) {
            val legacy = RecordMediaPreview(preview.snapshot, preview.images, preview.activities, preview.history,audios=preview.audios)
            val base = legacyMetadata(legacy)
            return try { RecordEditDraftPackageCodec.encode(preview, legacyVersion(legacy), base) } finally { base.fill(0) }
        }
        if (preview.snapshot.records.any { it.people.version > 0 }) {
            val legacy = RecordPeoplePackageCodec.legacyPreview(preview)
            val base = legacyMetadata(legacy)
            return try { RecordPeoplePackageCodec.encode(preview, legacyVersion(legacy), base) } finally { base.fill(0) }
        }
        if (preview.snapshot.records.any { it.eventDate.date != null }) {
            val baseVersion = if (preview.history != null) 4 else if (preview.activities != null) 3 else 2
            val base = legacyMetadata(RecordEventDatePackageCodec.legacyPreview(preview))
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
    private fun parseCurrentMetadata(metadata:ByteArray,v3:Boolean,audios:List<RecordPackageAudio> = emptyList()):AuthenticatedRecordPackage {
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
        reader.end();validate(original,images,audios)
        return CurrentMediaOriginal(original,images,activities)
    }
    private fun validate(original: AuthenticatedRecordPackage, images: List<RecordPackageImage>,audios:List<RecordPackageAudio>) {
        require(images.map { it.attachmentId }.distinct().size == images.size)
        require(images.sumOf { it.byteSize } <= RecordMediaPackages.MAX_IMAGE_TOTAL)
        (images+audios).forEach { i -> require(original.records.any { it.sourceId == i.sourceId && it.sourceRevision == i.sourceRevision }) }
        original.records.filter { it.originalText.isBlank() }.forEach { r ->
            require((images+audios).any { it.sourceId==r.sourceId && it.sourceRevision==r.sourceRevision })
        }
    }
    private inner class Prepared(private val header: ByteArray, private val metadata: ByteArray, private val key: ByteArray,
        private val images: List<RecordPackageAttachment>, private val read: suspend (RecordPackageAttachment) -> ByteArray,
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
        private val MAGIC7 = "AIYOUPK7".toByteArray(Charsets.US_ASCII)
        private val MAGIC9 = "AIYOUPK9".toByteArray(Charsets.US_ASCII)
        private val MAGICF13 = "AIYOUF13".toByteArray(Charsets.US_ASCII)
        private val MAGICF12 = "AIYOUF12".toByteArray(Charsets.US_ASCII)
        private val MAGICF11 = "AIYOUF11".toByteArray(Charsets.US_ASCII)
        private val MAGICF10 = "AIYOUF10".toByteArray(Charsets.US_ASCII)
        private val MAGICF9 = "AIYOUFB9".toByteArray(Charsets.US_ASCII)
        private val MAGIC8 = "AIYOUPK8".toByteArray(Charsets.US_ASCII)
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
        internal fun checkImage(image: RecordPackageAttachment, bytes: ByteArray) {
            require(bytes.size.toLong() == image.byteSize && packageHex(packageHash(bytes)) == image.sha256)
            if(image is RecordPackageAudio) { LocalPcmAudio.inspect(bytes);return }
            require(image is RecordPackageImage)
            // MIME is allowlisted and must also agree with the original file signature.
            require(if (image.mediaType == "image/png") bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))
                else bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte())
        }
    }
}

private class CurrentMediaOriginal(private val original:AuthenticatedRecordPackage, images:List<RecordPackageImage>,
    override val activities:RecordPackageActivities?): AuthenticatedRecordPackage by original {
    override val images=Collections.unmodifiableList(images.toList())
    override val media:List<RecordPackageAttachment> get()=images+audios
}

internal class AuthenticatedMediaPackage(private val original: AuthenticatedRecordPackage,
    attachments: List<RecordPackageAttachment>, override val digest: String, internal val header: ByteArray,
    private val key: ByteArray, internal val source: () -> InputStream, internal val cipher: RecordMediaPackageCipher) : AuthenticatedRecordPackage {
    override val manifest get() = original.manifest
    override val records get() = original.records
    override val recordDigests get() = original.recordDigests
    override val activities get() = original.activities
    override val includesHistory get() = original.includesHistory
    override val editDrafts get() = original.editDrafts
    override val images = Collections.unmodifiableList(attachments.filterIsInstance<RecordPackageImage>())
    override val audios=Collections.unmodifiableList(attachments.filterIsInstance<RecordPackageAudio>())
    private var released = false
    internal suspend fun <T> withKey(block: suspend (ByteArray) -> T): T {
        val borrowed = synchronized(this) { check(!released); key.copyOf() }
        return try { block(borrowed) } finally { borrowed.fill(0) }
    }
    @Synchronized override fun release() { released = true; key.fill(0) }
    override fun toString() = "AuthenticatedMediaPackage(images=${images.size}, redacted)"
}

private class AudioEvidence(private val original:AuthenticatedRecordPackage,audios:List<RecordPackageAudio>):AuthenticatedRecordPackage by original {
    override val audios=Collections.unmodifiableList(audios.toList())
    override val media:List<RecordPackageAttachment> get()=images+audios
}
