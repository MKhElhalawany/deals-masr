package com.dealsmasr.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Persistent delivery countdown computed from the stored confirmation timestamp
 * (never an in-memory timer), so it stays correct after the app is closed,
 * the device restarts, or the clock passes midnight.
 */
object OrderCountdown {
    val CAIRO: ZoneId = ZoneId.of("Africa/Cairo")
    const val DEFAULT_DAYS = 35
    const val MIN_DAYS = 25
    val MILESTONES = listOf(30, 21, 14, 7, 3, 1, 0)

    fun daysRemaining(confirmedAtMillis: Long, nowMillis: Long, totalDays: Int = DEFAULT_DAYS, zone: ZoneId = CAIRO): Int {
        val start = Instant.ofEpochMilli(confirmedAtMillis).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate()
        val elapsed = ChronoUnit.DAYS.between(start, today)
        return (totalDays - elapsed).coerceIn(0, totalDays.toLong()).toInt()
    }

    fun expectedDate(confirmedAtMillis: Long, totalDays: Int = DEFAULT_DAYS, zone: ZoneId = CAIRO): LocalDate =
        Instant.ofEpochMilli(confirmedAtMillis).atZone(zone).toLocalDate().plusDays(totalDays.toLong())

    fun progress(daysRemaining: Int, totalDays: Int = DEFAULT_DAYS): Float =
        if (totalDays <= 0) 1f else ((totalDays - daysRemaining).toFloat() / totalDays).coerceIn(0f, 1f)

    /**
     * Milestones reached but not yet notified. Only the most recent one should be shown;
     * all returned values should be marked as notified (avoids a burst after a long gap).
     */
    fun dueMilestones(daysRemaining: Int, notified: Set<Int>): List<Int> =
        MILESTONES.filter { daysRemaining <= it && it !in notified }

    /** "من 13 لـ 23 يوم" style window for an order expected in [minDays]–[maxDays]. */
    fun remainingRangeText(confirmedAtMillis: Long, nowMillis: Long, minDays: Int = MIN_DAYS, maxDays: Int = DEFAULT_DAYS): String {
        val hi = daysRemaining(confirmedAtMillis, nowMillis, maxDays)
        val lo = daysRemaining(confirmedAtMillis, nowMillis, minDays)
        return when {
            hi <= 0 -> "طلبك وصل لموعد التسليم المتوقع"
            lo <= 0 -> "طلبك ممكن يوصل في أي وقت خلال ${dayWord(hi)}"
            else -> "متبقي على وصول طلبك من $lo لـ ${dayWord(hi)}"
        }
    }

    fun dayWord(n: Int): String = when {
        n == 1 -> "يوم واحد"
        n == 2 -> "يومين"
        n in 3..10 -> "$n أيام"
        else -> "$n يوم"
    }

    fun remainingText(days: Int): String = when {
        days <= 0 -> "طلبك وصل لموعد التسليم المتوقع"
        days == 1 -> "متبقي على وصول طلبك يوم واحد"
        days == 2 -> "متبقي على وصول طلبك يومين"
        days in 3..10 -> "متبقي على وصول طلبك $days أيام"
        else -> "متبقي على وصول طلبك $days يوم"
    }
}
