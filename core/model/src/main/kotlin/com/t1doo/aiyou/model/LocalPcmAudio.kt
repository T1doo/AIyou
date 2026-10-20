package com.t1doo.aiyou.model

/** The single application-generated WAV profile. This is not an arbitrary-media decoder. */
object LocalPcmAudio {
    const val SAMPLE_RATE = 44100
    const val MAX_SECONDS = 60
    const val MAX_SAMPLES = SAMPLE_RATE * MAX_SECONDS
    const val MAX_WAV_BYTES = 44 + MAX_SAMPLES * 2
    data class Info(val samples: Int) { val durationMillis: Long get() = samples * 1000L / SAMPLE_RATE }

    fun encode(samples: ShortArray, count: Int = samples.size): ByteArray {
        require(count in 1..MAX_SAMPLES && count<=samples.size)
        return ByteArray(44+count*2).also { out ->
            fun text(at:Int,value:String) { value.forEachIndexed { index,c -> out[at+index]=c.code.toByte() } }
            fun number(at:Int,value:Int,size:Int) { repeat(size) { out[at+it]=(value ushr (8*it)).toByte() } }
            text(0,"RIFF");number(4,out.size-8,4);text(8,"WAVEfmt ");number(16,16,4)
            number(20,1,2);number(22,1,2);number(24,SAMPLE_RATE,4);number(28,SAMPLE_RATE*2,4)
            number(32,2,2);number(34,16,2);text(36,"data");number(40,count*2,4)
            repeat(count) { number(44+it*2,samples[it].toInt(),2) }
        }
    }

    fun inspect(bytes: ByteArray): Info {
        require(bytes.size in 46..MAX_WAV_BYTES && (bytes.size-44)%2==0)
        fun text(at:Int,value:String) = value.indices.all { bytes[at+it]==value[it].code.toByte() }
        fun number(at:Int,size:Int):Long = (0 until size).fold(0L) { n,i -> n or ((bytes[at+i].toLong() and 255) shl (8*i)) }
        require(text(0,"RIFF") && text(8,"WAVEfmt ") && text(36,"data"))
        require(number(4,4)==bytes.size-8L && number(16,4)==16L && number(20,2)==1L && number(22,2)==1L)
        require(number(24,4)==SAMPLE_RATE.toLong() && number(28,4)==SAMPLE_RATE*2L && number(32,2)==2L && number(34,2)==16L)
        require(number(40,4)==bytes.size-44L)
        return Info((bytes.size-44)/2)
    }
}
