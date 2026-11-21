package com.dealsmasr.app.core

import java.math.BigDecimal
import java.math.RoundingMode

/** A configurable discount tier (min_pct inclusive). Mirrors backend/app/pricing.py. */
data class DealTier(val key: String, val labelAr: String, val minPct: Double)

/** Fields needed to sort deals; implemented by cached deal entities and API models. */
interface Sortable {
    val discountPct: Double
    val currentPrice: Double
    val dealSince: Long
    val ratingCount: Int?
    val rating: Double?
}

enum class DealSort(val apiKey: String, val labelAr: String) {
    DISCOUNT("discount", "أعلى خصم"),
    PRICE_ASC("price_asc", "الأقل سعراً"),
    PRICE_DESC("price_desc", "الأعلى سعراً"),
    NEWEST("newest", "الأحدث"),
    POPULAR("popular", "الأكثر شعبية"),
}

object DealMath {
    val DEFAULT_TIERS = listOf(
        DealTier("deal", "عروض", 10.0),
        DealTier("hot", "عروض ساخنة 🔥", 30.0),
        DealTier("super", "سوبر ديلز", 50.0),
        DealTier("mega", "ميجا ديلز", 70.0),
    )

    /** ((original - current) / original) × 100, 1 decimal. Null when there is no real discount. */
    fun discountPercent(original: Double?, current: Double?): Double? {
        if (original == null || current == null) return null
        if (original <= 0.0 || current <= 0.0 || current >= original) return null
        val o = BigDecimal.valueOf(original)
        val c = BigDecimal.valueOf(current)
        return o.subtract(c).multiply(BigDecimal(100)).divide(o, 10, RoundingMode.HALF_UP)
            .setScale(1, RoundingMode.HALF_UP).toDouble()
    }

    fun savings(original: Double, current: Double): Double =
        BigDecimal.valueOf(original).subtract(BigDecimal.valueOf(current)).setScale(2, RoundingMode.HALF_UP).toDouble()

    fun classify(pct: Double?, tiers: List<DealTier> = DEFAULT_TIERS): DealTier? {
        if (pct == null) return null
        return tiers.sortedBy { it.minPct }.lastOrNull { pct >= it.minPct }
    }

    /** [min, maxExclusive) for a tier key; max is null for the top tier. */
    fun tierRange(key: String, tiers: List<DealTier> = DEFAULT_TIERS): Pair<Double, Double?>? {
        val sorted = tiers.sortedBy { it.minPct }
        val i = sorted.indexOfFirst { it.key == key }
        if (i < 0) return null
        return sorted[i].minPct to sorted.getOrNull(i + 1)?.minPct
    }

    fun <T : Sortable> sort(items: List<T>, sort: DealSort): List<T> = when (sort) {
        DealSort.DISCOUNT -> items.sortedWith(compareByDescending<T> { it.discountPct }.thenBy { it.currentPrice })
        DealSort.PRICE_ASC -> items.sortedBy { it.currentPrice }
        DealSort.PRICE_DESC -> items.sortedByDescending { it.currentPrice }
        DealSort.NEWEST -> items.sortedByDescending { it.dealSince }
        DealSort.POPULAR -> items.sortedWith(
            compareByDescending<T> { it.ratingCount ?: 0 }.thenByDescending { it.rating ?: 0.0 }.thenByDescending { it.discountPct }
        )
    }

    val MIN_DISCOUNT_OPTIONS = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90)
}
