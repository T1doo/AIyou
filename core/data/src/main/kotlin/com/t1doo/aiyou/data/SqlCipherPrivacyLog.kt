package com.t1doo.aiyou.data

/** SQLCipher's default open-error logger includes a private DB path and raw exception. Keep only a
 * fixed status. This does not alter or delete application/test evidence, nor hide UI failure states. */
internal object SqlCipherPrivacyLog:net.zetetic.database.LogTarget {
    fun install(){net.zetetic.database.Logger.setTarget(this)}
    override fun isLoggable(tag:String?,priority:Int)=priority>=android.util.Log.WARN
    override fun log(priority:Int,tag:String?,message:String?,failure:Throwable?){
        if(priority>=android.util.Log.WARN)android.util.Log.w("AIyouStorage","Encrypted storage operation unavailable")
    }
}
