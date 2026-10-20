package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.StorageKeyUnavailable
import com.t1doo.aiyou.domain.ReminderRuntimeClosureUnconfirmed
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/** Production pre-key existing-only protocol; host tests replace only key/native platform boundaries. */
internal object ExistingReminderOpenProtocol {
    fun <T> open(root:File,unlocked:()->Boolean,loadExistingKey:(Boolean)->ByteArray,nativeOpen:(File,ByteArray)->T):T {
        if(!unlocked())throw StorageKeyUnavailable()
        fun attributes(file:File)=try{Files.readAttributes(file.toPath(),BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)}catch(_:Exception){throw StorageKeyUnavailable()}
        val rootBefore=attributes(root);val records=File(root,"records");val directoryBefore=attributes(records)
        requireExisting(rootBefore,true);requireExisting(directoryBefore,true)
        val file=File(records,"records.db");val before=attributes(file);requireExisting(before,false)
        val key=loadExistingKey(true)
        var opened=false
        try{
            require(key.size==32)
            val after=attributes(file);requireExisting(after,false)
            if(before.fileKey()!=after.fileKey() || directoryBefore.fileKey()!=attributes(records).fileKey() || rootBefore.fileKey()!=attributes(root).fileKey())throw StorageKeyUnavailable()
            return nativeOpen(file,key).also{opened=true}
        }catch(f:ReminderRuntimeClosureUnconfirmed){opened=true;throw f}
        finally{if(!opened)key.fill(0)}
    }
    private fun requireExisting(a:BasicFileAttributes,directory:Boolean){
        if(a.isSymbolicLink || a.fileKey()==null || (if(directory)!a.isDirectory else !a.isRegularFile || a.size()<=0))throw StorageKeyUnavailable()
    }
}
