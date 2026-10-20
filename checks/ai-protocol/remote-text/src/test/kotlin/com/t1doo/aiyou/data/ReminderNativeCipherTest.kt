package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.*
import com.t1doo.aiyou.model.*
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test

class ReminderNativeCipherTest {
    private val golden=Properties().apply{ReminderNativeCipherTest::class.java.getResourceAsStream("/reminder-native-fixtures.properties").use{load(checkNotNull(it))}}
    private fun bytes(n:String)=Base64.getDecoder().decode(golden.getProperty(n))
    @Test fun independentNodeDigestAndMetadataBytesMatchActualWriterAndReader():Unit {
        val fp=ReminderPlatformFingerprint(12,"UTC",2,RuntimeFixture.AVAILABLE)
        val request=ReminderRebuildRequest.create(7,ReminderRebuildRequest.BOOT,fp,"44".repeat(16))
        assertEquals(golden.getProperty("fingerprint"),fp.digest);assertEquals(golden.getProperty("requestId"),request.id)
        val expected=ReminderRuntimeMeta(9,"gold-meta",request)
        ReminderRuntimeMetaCipher(LocalCandidateFixture.KEY).use{c->
            assertEquals(expected,c.open(9,bytes("metaCell")))
            val actual=c.seal(expected)
            val key=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(LocalCandidateFixture.KEY,"HmacSHA256"));doFinal("AIyou/reminder-runtime-meta/key/1".toByteArray(Charsets.US_ASCII))}
            // Frozen independent plaintext, not a second call through the product decoder.
            // Build the length independently to avoid assuming the namespace's character count.
            val name="AIyou/reminder-runtime-meta/1".toByteArray();val bound=java.nio.ByteBuffer.allocate(4+name.size+4+8).putInt(name.size).put(name).putInt(1).putLong(9).array()
            val plain=Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,actual.copyOfRange(1,13)));updateAAD(bound);doFinal(actual,13,actual.size-13)}
            assertArrayEquals(bytes("metaPlain"),plain);plain.fill(0);key.fill(0)
            assertThrows(Exception::class.java){c.open(10,actual)}
            ReminderRuntimeMetaCipher(ByteArray(32){9}).use{wrong->assertThrows(Exception::class.java){wrong.open(9,actual)}}
        }
    }
    @Test fun actualMaximumUnicodeMetadataAndEnvelopeStayWithinBudgetsAndDecodeWithoutLoss():Unit {
        val id="界".repeat(128);val fp=ReminderPlatformFingerprint(Long.MAX_VALUE,"America/Argentina/ComodRivadavia",Long.MAX_VALUE,RuntimeFixture.AVAILABLE)
        val request=ReminderRebuildRequest.create(7,127,fp)
        val meta=ReminderRuntimeMeta(9,id,request,id,id,false,ReminderDrainPass("12345678-1234-1234-1234-123456789abc",id,ReminderScanCursor(id,Long.MAX_VALUE,false),false,true),Int.MAX_VALUE)
        ReminderRuntimeMetaCipher(LocalCandidateFixture.KEY).use{c->val cell=c.seal(meta);assertTrue(cell.size<=4096);assertEquals(meta,c.open(9,cell));assertThrows(Exception::class.java){c.open(9,cell.copyOf(4097))};assertThrows(Exception::class.java){c.open(9,cell+byteArrayOf(0))}}
        ReminderEnvelopeCipher(LocalCandidateFixture.KEY).use{c->
            val token=TaskReminderToken(id,TaskReminders.id(id),Long.MAX_VALUE);val a=c.seal(token,ReminderEnvelopePurpose.ALARM);val b=c.seal(token,ReminderEnvelopePurpose.ALARM)
            assertEquals(a.identity,b.identity);assertFalse(a.bytes().contentEquals(b.bytes()));assertTrue(a.bytes().size<=2048)
            assertEquals(token,c.open(a,ReminderEnvelopePurpose.ALARM));assertThrows(Exception::class.java){c.open(a,ReminderEnvelopePurpose.CLICK)}
            val copy=a.bytes();copy.fill(0);assertEquals(token,c.open(a,ReminderEnvelopePurpose.ALARM))
            val altered=a.bytes();altered[altered.lastIndex]=(altered.last().toInt() xor 1).toByte();assertThrows(Exception::class.java){c.open(ReminderEnvelope(a.identity,altered),ReminderEnvelopePurpose.ALARM)}
        }
    }
    @Test fun independentEnvelopeCannotCrossPurposePackageOrDeviceAndClosedCodecCannotRetainAuthority():Unit {
        val envelope=ReminderEnvelope(golden.getProperty("identity"),bytes("envelope"));val token=TaskReminderToken("合成🙂",golden.getProperty("reminderId"),3)
        val c=ReminderEnvelopeCipher(LocalCandidateFixture.KEY);assertEquals(token,c.open(envelope,ReminderEnvelopePurpose.ALARM));assertEquals(envelope.identity,c.identity(token))
        ReminderEnvelopeCipher(LocalCandidateFixture.KEY,"synthetic.other").use{wrong->assertThrows(Exception::class.java){wrong.open(envelope,ReminderEnvelopePurpose.ALARM)}}
        ReminderEnvelopeCipher(ByteArray(32){8}).use{wrong->assertThrows(Exception::class.java){wrong.open(envelope,ReminderEnvelopePurpose.ALARM)}}
        c.close();assertThrows(IllegalStateException::class.java){c.open(envelope,ReminderEnvelopePurpose.ALARM)}
    }
    @Test fun independentlyAuthenticatedMalformedMetadataIsRejectedAfterGcmVerification():Unit {
        val original=bytes("metaPlain")
        val input=java.io.DataInputStream(original.inputStream());input.readInt();input.skipBytes(input.readInt());val flagOffset=original.size-input.available()
        val invalid=listOf(original.copyOf().also{it[flagOffset]=2},original.copyOf().also{it[8]=0xC0.toByte()},original+byteArrayOf(0),original.copyOf().also{it[3]=2})
        ReminderRuntimeMetaCipher(LocalCandidateFixture.KEY).use{c->
            for(raw in invalid){
                val key=Mac.getInstance("HmacSHA256").run{init(SecretKeySpec(LocalCandidateFixture.KEY,"HmacSHA256"));doFinal("AIyou/reminder-runtime-meta/key/1".toByteArray(Charsets.US_ASCII))}
                val name="AIyou/reminder-runtime-meta/1".toByteArray();val aad=java.nio.ByteBuffer.allocate(4+name.size+12).putInt(name.size).put(name).putInt(1).putLong(9).array()
                val nonce=ByteArray(12).also{java.security.SecureRandom().nextBytes(it)}
                val cell=Cipher.getInstance("AES/GCM/NoPadding").run{init(Cipher.ENCRYPT_MODE,SecretKeySpec(key,"AES"),GCMParameterSpec(128,nonce));updateAAD(aad);byteArrayOf(1)+nonce+doFinal(raw)}
                assertThrows(Exception::class.java){c.open(9,cell)};key.fill(0);raw.fill(0)
            }
        }
        original.fill(0)
    }
}
