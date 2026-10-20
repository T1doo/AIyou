package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.AttachmentStore
import com.t1doo.aiyou.domain.CaptureDraftGate
import com.t1doo.aiyou.model.AttachmentIntent
import java.io.InputStream

/** One production owner gate; reads never invoke the backing store's implicit recovery/marking. */
internal class OwnerAttachmentStore(private val storage:EncryptedAttachmentStore,private val gate:CaptureDraftGate):AttachmentStore {
    override suspend fun save(intent:AttachmentIntent,source:()->InputStream)=gate.sourceMutation { storage.save(intent,source) }
    override suspend fun recover()=gate.sourceMutation { storage.recover() }
    override suspend fun list(entryId:String,revision:Int)=storage.listReadOnly(entryId,revision)
    override suspend fun read(id:String)=storage.readReadOnly(id)
}
