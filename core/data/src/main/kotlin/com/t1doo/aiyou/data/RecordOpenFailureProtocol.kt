package com.t1doo.aiyou.data

import com.t1doo.aiyou.domain.ReminderRuntimeClosureUnconfirmed

/** Used by the actual foreground factory before it can return an owner. A failed close/drain is
 * not a successful empty cleanup; retain native/file handles and the master key for quarantine. */
internal object RecordOpenFailureProtocol {
    fun cleanup(key:ByteArray,resources:Any?,closeAndDrain:()->Unit){
        try{closeAndDrain()}
        catch(failure:Throwable){throw ReminderRuntimeClosureUnconfirmed(arrayOf(resources,key,failure)){closeAndDrain();key.fill(0)}}
        key.fill(0)
    }
}
