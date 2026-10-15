package com.t1doo.aiyou.model

import java.time.*
import java.time.temporal.TemporalAdjusters

enum class FactRecapPeriod { DAY, WEEK }

/** Calendar boundaries in the user's explicit zone, never a fixed 24-hour duration. */
data class FactRecapRange(val date: LocalDate, val period: FactRecapPeriod, val zone: String) {
    init { require(date.year in 1900..2100); ZoneId.of(zone) }
    val firstDay: LocalDate = if(period==FactRecapPeriod.DAY) date else date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val endDay: LocalDate = firstDay.plusDays(if(period==FactRecapPeriod.DAY) 1 else 7)
    val startMillis: Long = firstDay.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
    val endMillis: Long = endDay.atStartOfDay(ZoneId.of(zone)).toInstant().toEpochMilli()
    val days: List<LocalDate> = (0 until if(period==FactRecapPeriod.DAY) 1 else 7).map { firstDay.plusDays(it.toLong()) }
    fun day(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneId.of(zone)).toLocalDate()
    override fun toString()="FactRecapRange(redacted)"
}

data class FactRecapImage(val intent: AttachmentIntent, val state: AttachmentState)

/** Bounded original excerpt; never an invented summary or an image interpretation. */
data class FactRecapCard(val source: SourceVersion, val capturedAtMillis: Long, val captureZone: String,
    val contentHash: String, val sourceGeneration: Long, val excerpt: String, val hasMore: Boolean,
    val tags: ManualTagSnapshot, val images: List<FactRecapImage>, val policy: ProcessingPolicy) {
    fun same(other: FactRecapCard)=source==other.source && capturedAtMillis==other.capturedAtMillis &&
        captureZone==other.captureZone && contentHash==other.contentHash && sourceGeneration==other.sourceGeneration &&
        excerpt==other.excerpt && hasMore==other.hasMore && tags.version==other.tags.version &&
        tags.names==other.tags.names && images==other.images && policy==other.policy
    override fun toString()="FactRecapCard(redacted)"
}
