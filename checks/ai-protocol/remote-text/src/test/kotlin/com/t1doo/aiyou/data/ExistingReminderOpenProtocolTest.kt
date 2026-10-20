package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.StorageKeyUnavailable
import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

/** Actual filesystem protocol. Key unwrap / native SQLCipher remain explicit controlled boundaries. */
class ExistingReminderOpenProtocolTest {
    @Test fun noFirstUnlockOrAbsentPrimaryCannotLoadKeysCreateFilesOrReachNativeOpen():Unit=fixture{root->
        var keys=0;var native=0
        fun attempt(unlocked:Boolean)=ExistingReminderOpenProtocol.open(root,{unlocked},{keys++;ByteArray(32)},{_,_->native++;Unit})
        assertThrows(StorageKeyUnavailable::class.java){attempt(false)}
        assertFalse(File(root,"records").exists());assertEquals(0,keys);assertEquals(0,native)
        val directory=File(root,"records").also{it.mkdir()};File(directory,"records.db-wal").writeBytes(byteArrayOf(1,2,3))
        assertThrows(StorageKeyUnavailable::class.java){attempt(true)}
        assertFalse(File(directory,"records.db").exists());assertEquals(0,keys);assertEquals(0,native)
        File(directory,"records.db").writeBytes(byteArrayOf())
        assertThrows(StorageKeyUnavailable::class.java){attempt(true)};assertEquals(0,keys);assertEquals(0,native)
    }
    @Test fun failedKeyAndNativeSchemaOrCorruptionPreserveOwnedBytesAndNeverUseACreateFallback():Unit=fixture{root->
        val records=File(root,"records").also{it.mkdir()};val file=File(records,"records.db").also{it.writeBytes(ByteArray(64){n->n.toByte()})}
        val before=file.readBytes();var native=0
        assertThrows(StorageKeyUnavailable::class.java){ExistingReminderOpenProtocol.open(root,{true},{existing->assertTrue(existing);throw StorageKeyUnavailable()},{_,_->native++})}
        assertEquals(0,native);assertArrayEquals(before,file.readBytes())
        for(kind in listOf("wrong-key","bad-schema","corruption")){
            val raw=ByteArray(32){7}
            assertThrows(StorageKeyUnavailable::class.java){ExistingReminderOpenProtocol.open(root,{true},{existing->assertTrue(existing);raw},{found,key->
                assertEquals(file,found);assertSame(raw,key);native++;throw StorageKeyUnavailable()
            })}
            assertTrue(raw.all{it==0.toByte()});assertArrayEquals(before,file.readBytes())
            assertEquals(listOf("records.db"),records.list()!!.toList());assertTrue(kind.isNotEmpty())
        }
        assertEquals(3,native)
    }
    @Test fun symlinkAndReplacementDuringKeyLoadFailBeforeNativeOpenAndWipeTheLoadedKey():Unit=fixture{root->
        val records=File(root,"records").also{it.mkdir()};val file=File(records,"records.db");val target=File(root,"synthetic").also{it.writeBytes(ByteArray(64){5})}
        Files.createSymbolicLink(file.toPath(),target.toPath());var keys=0;var native=0
        assertThrows(StorageKeyUnavailable::class.java){ExistingReminderOpenProtocol.open(root,{true},{keys++;ByteArray(32)},{_,_->native++})}
        assertEquals(0,keys);assertEquals(0,native);Files.delete(file.toPath());file.writeBytes(ByteArray(64){1})
        val replacement=File(records,"replacement").also{it.writeBytes(ByteArray(64){2})};val key=ByteArray(32){3}
        assertThrows(StorageKeyUnavailable::class.java){ExistingReminderOpenProtocol.open(root,{true},{existing->
            assertTrue(existing);Files.move(replacement.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);key
        },{_,_->native++})}
        assertEquals(0,native);assertTrue(key.all{it==0.toByte()});assertArrayEquals(ByteArray(64){2},file.readBytes())
    }
    @Test fun unconfirmedCloseQuarantinesTheLiveKeyInsteadOfAcknowledgingWipe():Unit=fixture{root->
        val records=File(root,"records").also{it.mkdir()};File(records,"records.db").writeBytes(ByteArray(64){1})
        val raw=ByteArray(32){9}
        assertThrows(com.t1doo.aiyou.domain.ReminderRuntimeClosureUnconfirmed::class.java){
            ExistingReminderOpenProtocol.open(root,{true},{raw},{_,key->throw com.t1doo.aiyou.domain.ReminderRuntimeClosureUnconfirmed(key)})
        }
        assertArrayEquals(ByteArray(32){9},raw);raw.fill(0) // Test owns the synthetic retained handle.
    }
    private inline fun fixture(action:(File)->Unit){
        val root=Files.createTempDirectory("aiyou-existing-reminder-")
        try{action(root.toFile())}finally{Files.walk(root).use{it.sorted(Comparator.reverseOrder()).forEach(Files::delete)}}
    }
}
