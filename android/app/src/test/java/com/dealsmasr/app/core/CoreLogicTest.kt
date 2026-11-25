package com.dealsmasr.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

class CoreLogicTest {
    private val cairo = ZoneId.of("Africa/Cairo")
    private fun millis(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        LocalDateTime.of(y, m, d, h, min).atZone(cairo).toInstant().toEpochMilli()

    @Test fun discountFormula() {
        assertEquals(30.0, DealMath.discountPercent(1000.0, 700.0)!!, 0.0)
        assertEquals(66.7, DealMath.discountPercent(999.0, 333.0)!!, 0.0)
        assertNull(DealMath.discountPercent(100.0, 100.0))
        assertNull(DealMath.discountPercent(100.0, 120.0))
        assertNull(DealMath.discountPercent(0.0, 10.0))
        assertNull(DealMath.discountPercent(null, 10.0))
        assertEquals(350.5, DealMath.savings(1000.0, 649.5), 0.0)
    }

    @Test fun tierBoundaries() {
        val cases = mapOf(9.9 to null, 10.0 to "deal", 29.9 to "deal", 30.0 to "hot", 49.9 to "hot",
            50.0 to "super", 69.9 to "super", 70.0 to "mega", 95.0 to "mega")
        cases.forEach { (pct, key) -> assertEquals("pct=$pct", key, DealMath.classify(pct)?.key) }
        assertEquals(30.0 to 50.0, DealMath.tierRange("hot"))
        assertEquals(70.0 to null, DealMath.tierRange("mega"))
    }

    @Test fun sheinSpecExample() {
        val b = SheinPricing.order(listOf(BigDecimal("100") to 1), SheinPricingConfig())
        assertEquals(BigDecimal("1400.00"), b.convertedEgp)
        assertEquals(BigDecimal("280.00"), b.extraEgp)
        assertEquals(BigDecimal("1680.00"), b.subtotalEgp)
        assertEquals(BigDecimal("130"), b.deliveryFeeEgp)
        assertEquals(BigDecimal("1810.00"), b.totalEgp)
    }

    @Test fun sheinDeliveryOncePerOrder() {
        val b = SheinPricing.order(
            listOf(BigDecimal("100") to 1, BigDecimal("50") to 2, BigDecimal("19.99") to 3), SheinPricingConfig()
        )
        assertEquals(BigDecimal("130"), b.deliveryFeeEgp)
        assertEquals(BigDecimal("4367.50"), b.subtotalEgp)
        assertEquals(BigDecimal("4497.50"), b.totalEgp)
        val withFee = SheinPricing.order(listOf(BigDecimal("100") to 1, BigDecimal("50") to 2), SheinPricingConfig.of("14.5", "1.20", "130"))
        assertEquals(BigDecimal("130"), withFee.deliveryFeeEgp)
        assertEquals(BigDecimal.ZERO, SheinPricing.order(emptyList(), SheinPricingConfig()).totalEgp)
        val custom = SheinPricingConfig.of("14", "1.1", "150")
        assertEquals(BigDecimal("304.00"), SheinPricing.order(listOf(BigDecimal("10") to 1), custom).totalEgp)
    }

    @Test fun countdownIsCalendarBasedAndPersistent() {
        val confirmed = millis(2026, 11, 1, 23, 50)
        assertEquals(35, OrderCountdown.daysRemaining(confirmed, millis(2026, 11, 1, 23, 59)))
        assertEquals(34, OrderCountdown.daysRemaining(confirmed, millis(2026, 11, 2, 0, 5)))
        assertEquals(23, OrderCountdown.daysRemaining(confirmed, millis(2026, 11, 13)))
        assertEquals(0, OrderCountdown.daysRemaining(confirmed, millis(2026, 12, 6)))
        assertEquals(0, OrderCountdown.daysRemaining(confirmed, millis(2027, 3, 1)))
        assertEquals(35, OrderCountdown.daysRemaining(confirmed, millis(2026, 10, 1)))  // clock skew
        assertEquals("2026-12-06", OrderCountdown.expectedDate(confirmed).toString())
        assertEquals("متبقي على وصول طلبك 23 يوم", OrderCountdown.remainingText(23))
        assertEquals("متبقي على وصول طلبك 5 أيام", OrderCountdown.remainingText(5))
    }

    @Test fun milestones() {
        assertEquals(emptyList<Int>(), OrderCountdown.dueMilestones(33, emptySet()))
        assertEquals(listOf(30), OrderCountdown.dueMilestones(30, emptySet()))
        assertEquals(listOf(14, 7), OrderCountdown.dueMilestones(6, setOf(30, 21)))
    }

    @Test fun sheinLinks() {
        assertEquals("12345678", SheinLink.extractGoodsId("https://ar.shein.com/SHEIN-Dress-p-12345678-cat-1727.html?src=x"))
        assertEquals("55556666", SheinLink.extractGoodsId("https://shein.com/goods_detail?goods_id=55556666"))
        assertEquals("11223344", SheinLink.extractGoodsId("https://onelink.shein.com/x?url=https%3A%2F%2Fsa.shein.com%2Fa-p-11223344.html"))
        assertNull(SheinLink.extractGoodsId("https://example.com/x"))
        assertEquals("https://eg.shein.com/a-p-1.html", SheinLink.firstUrl("شوف ده https://eg.shein.com/a-p-1.html."))
        assertTrue(SheinLink.isSheinUrl("https://m.shein.com/eg/x"))
    }

    @Test fun whatsappSummary() {
        val cfg = SheinPricingConfig()
        val b = SheinPricing.order(listOf(BigDecimal("100") to 1), cfg)
        val text = OrderSummary.whatsappText(
            SummaryOrder("A1B2", "أحمد", "01012345678", null,
                listOf(SummaryItem("فستان", "https://sa.shein.com/x-p-1.html", null, "M", "أسود", null, 1, b.lines[0])),
                b, millis(2026, 11, 21))
        )
        assertTrue(text.contains("الإجمالي المطلوب: 1,810 ج.م"))
        assertTrue(text.contains("المقاس: M | اللون: أسود | الكمية: 1"))
        assertTrue(text.contains("التوصيل: 130 ج.م"))
    }

    @Test fun sorting() {
        data class D(override val discountPct: Double, override val currentPrice: Double, override val dealSince: Long,
                     override val ratingCount: Int?, override val rating: Double?) : Sortable
        val a = D(30.0, 500.0, 3, 10, 4.0); val b = D(60.0, 900.0, 1, null, null); val c = D(45.0, 100.0, 2, 999, 4.5)
        assertEquals(listOf(b, c, a), DealMath.sort(listOf(a, b, c), DealSort.DISCOUNT))
        assertEquals(listOf(c, a, b), DealMath.sort(listOf(a, b, c), DealSort.PRICE_ASC))
        assertEquals(listOf(a, c, b), DealMath.sort(listOf(a, b, c), DealSort.NEWEST))
        assertEquals(listOf(c, a, b), DealMath.sort(listOf(a, b, c), DealSort.POPULAR))
    }
}
