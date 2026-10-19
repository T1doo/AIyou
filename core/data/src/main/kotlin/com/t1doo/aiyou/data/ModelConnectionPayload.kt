package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.*

/** v1 is retained verbatim for metadata-only edits; v2 carries a reference and an AEAD cell. */
internal object ModelConnectionPayload {
    data class Value(val details: ModelConnectionDetails, val kind: Int = 0,
        val receipt: SecretReference? = null, val cell: ByteArray? = null)
    fun encode(v: Value): ByteArray = ByteArrayOutputStream().use { b ->
        DataOutputStream(b).use { out ->
            out.writeInt(if(v.kind==0) 1 else 2)
            listOf(v.details.name,v.details.apiRoot,v.details.protocol.name,v.details.modelId).forEach(out::writeUTF)
            out.writeInt(v.details.purposes.size);v.details.purposes.sortedBy { it.ordinal }.forEach { out.writeUTF(it.name) }
            if(v.kind!=0) { require(v.kind in 1..2);out.writeInt(v.kind);out.writeUTF(checkNotNull(v.receipt).value)
                out.writeInt(v.cell?.size?:0);v.cell?.let(out::write) }
        };b.toByteArray().also { require(it.size in 1..8192) }
    }
    fun decode(raw: ByteArray): Value {
        require(raw.size in 1..8192)
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            val codec=input.readInt();require(codec in 1..2)
            val name=input.readUTF();val root=input.readUTF();val protocol=ConnectionProtocol.valueOf(input.readUTF());val model=input.readUTF()
            val count=input.readInt();require(count in 1..5)
            val purposes=List(count){ModelConfigurationPurpose.valueOf(input.readUTF())};require(purposes.distinct().size==count)
            val details=ModelConnectionDetails(name,root,protocol,model,purposes.toSet())
            val value=if(codec==1) Value(details) else {
                val kind=input.readInt();require(kind in 1..2)
                val ref=SecretReference(input.readUTF());require(ref.value.startsWith("vault-v1:"))
                val size=input.readInt();require(size in 0..8192 && size<=input.available())
                require(if(kind==1) size>=29 else size==0)
                Value(details,kind,ref,if(size==0) null else ByteArray(size).also(input::readFully))
            }
            require(input.read()==-1);value
        }
    }
}
