package com.t1doo.aiyou.model

/** A human-marked half-open interval in the immutable canonical PCM source. */
data class AudioSampleRange(val startFrame: Int, val endFrameExclusive: Int) {
    init { require(startFrame >= 0 && endFrameExclusive > startFrame && endFrameExclusive <= LocalPcmAudio.MAX_SAMPLES) }
    fun validate(totalSamples: Int) { require(totalSamples in 1..LocalPcmAudio.MAX_SAMPLES && endFrameExclusive <= totalSamples) }
    override fun toString() = "AudioSampleRange(redacted)"

    companion object {
        private val seconds = Regex("[0-9]{1,2}(?:\\.[0-9]{1,3})?")
        /** Display durationMillis is floored and is never an admission bound. */
        fun parse(from: String, through: String, endsAtSource: Boolean, totalSamples: Int): AudioSampleRange {
            if (totalSamples !in 1..LocalPcmAudio.MAX_SAMPLES) throw AudioSampleRangeInvalid(AudioSampleRangeProblem.BOUNDS)
            fun millis(value: String, problem: AudioSampleRangeProblem): Long {
                if (value.length !in 1..6 || !seconds.matches(value)) throw AudioSampleRangeInvalid(problem)
                val point = value.indexOf('.')
                val whole = (if (point < 0) value else value.substring(0, point)).toLong()
                val fraction = if (point < 0) 0L else value.substring(point + 1).padEnd(3, '0').toLong()
                val result = Math.addExact(Math.multiplyExact(whole, 1000L), fraction)
                if (result > LocalPcmAudio.MAX_SECONDS * 1000L) throw AudioSampleRangeInvalid(problem)
                return result
            }
            fun frame(value: Long): Int {
                val scaled = Math.multiplyExact(value, LocalPcmAudio.SAMPLE_RATE.toLong())
                if (scaled > Math.multiplyExact(totalSamples.toLong(), 1000L)) throw AudioSampleRangeInvalid(AudioSampleRangeProblem.BOUNDS)
                return Math.toIntExact(Math.addExact(scaled, 999L) / 1000L)
            }
            val start = frame(millis(from, AudioSampleRangeProblem.START))
            val end = if (endsAtSource) {
                if (through.isNotEmpty()) throw AudioSampleRangeInvalid(AudioSampleRangeProblem.END)
                totalSamples
            } else frame(millis(through, AudioSampleRangeProblem.END))
            if (start >= end) throw AudioSampleRangeInvalid(AudioSampleRangeProblem.ORDER)
            return AudioSampleRange(start, end).also { it.validate(totalSamples) }
        }
    }
}

enum class AudioSampleRangeProblem { START, END, ORDER, BOUNDS }
class AudioSampleRangeInvalid(val problem: AudioSampleRangeProblem) : IllegalArgumentException("Invalid manual audio range")
