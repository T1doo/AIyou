package com.t1doo.aiyou.data

import com.t1doo.aiyou.model.*
import java.io.*

/** Codec/sample contract v1. Contains metadata and fixed diagnostics, never resolved keys or provider output. */
internal object ModelCapabilityPayload {
    fun encode(profile: ModelCapabilityProfile): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(1)
            val c=profile.connection
            out.writeUTF(c.id);out.writeLong(c.version);out.writeUTF(checkNotNull(c.secretRef).value)
            val details=ModelConnectionPayload.encode(ModelConnectionPayload.Value(c.details))
            out.writeInt(details.size);out.write(details)
            out.writeUTF(profile.probeId);out.writeLong(profile.generation);out.writeLong(profile.startedAtMillis)
            out.writeUTF(profile.observation.state.name);out.writeUTF(profile.observation.reason.name);out.writeLong(profile.observation.checkedAtMillis)
        };bytes.toByteArray().also { require(it.size in 1..12288) }
    }
    fun decode(raw: ByteArray): ModelCapabilityProfile {
        require(raw.size in 1..12288)
        return DataInputStream(ByteArrayInputStream(raw)).use { input ->
            require(input.readInt()==1)
            val id=input.readUTF();val version=input.readLong();val ref=SecretReference(input.readUTF())
            val size=input.readInt();require(size in 1..8192 && size<=input.available())
            val details=ModelConnectionPayload.decode(ByteArray(size).also(input::readFully)).also { require(it.kind==0) }.details
            val probe=input.readUTF();val generation=input.readLong();val started=input.readLong()
            val result=TextCapabilityObservation(CapabilityState.valueOf(input.readUTF()),CapabilityProbeReason.valueOf(input.readUTF()),input.readLong())
            require(input.read()==-1)
            ModelCapabilityProfile(ModelConnectionConfiguration(id,version,details,ref),probe,generation,started,result)
        }
    }
}
