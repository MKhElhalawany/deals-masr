package com.dealsmasr.app.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Final = (SAR original price × rate × multiplier) summed over items + delivery fee ONCE per order (now 0: included). */
data class SheinPricingConfig(
    val sarToEgp: BigDecimal = BigDecimal("14.5"),
    val multiplier: BigDecimal = BigDecimal("1.20"),
    val deliveryFeeEgp: BigDecimal = BigDecimal.ZERO,
) {
    companion object {
        fun of(rate: String?, multiplier: String?, fee: String?) = SheinPricingConfig(
            rate?.toBigDecimalOrNull() ?: BigDecimal("14.5"),
            multiplier?.toBigDecimalOrNull() ?: BigDecimal("1.20"),
            fee?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
        )
    }
}

data class LineBreakdown(
    val sarUnit: BigDecimal,
    val quantity: Int,
    val sarTotal: BigDecimal,
    val convertedEgp: BigDecimal,
    val extraEgp: BigDecimal,
    val lineTotalEgp: BigDecimal,
)

data class OrderBreakdown(
    val lines: List<LineBreakdown>,
    val sarTotal: BigDecimal,
    val convertedEgp: BigDecimal,
    val extraEgp: BigDecimal,
    val subtotalEgp: BigDecimal,
    val deliveryFeeEgp: BigDecimal,
    val totalEgp: BigDecimal,
)

object SheinPricing {
    private fun q(x: BigDecimal) = x.setScale(2, RoundingMode.HALF_UP)

    fun line(sarUnit: BigDecimal, quantity: Int, cfg: SheinPricingConfig): LineBreakdown {
        require(quantity >= 1) { "quantity must be >= 1" }
        require(sarUnit > BigDecimal.ZERO) { "price must be > 0" }
        val sarTotal = sarUnit.multiply(BigDecimal(quantity))
        val converted = sarTotal.multiply(cfg.sarToEgp)
        val total = converted.multiply(cfg.multiplier)
        return LineBreakdown(sarUnit, quantity, q(sarTotal), q(converted), q(total.subtract(converted)), q(total))
    }

    /** items = (SAR unit price, quantity). */
    fun order(items: List<Pair<BigDecimal, Int>>, cfg: SheinPricingConfig): OrderBreakdown {
        val lines = items.map { (p, n) -> line(p, n, cfg) }
        val subtotal = lines.fold(BigDecimal.ZERO) { a, l -> a + l.lineTotalEgp }
        val fee = if (lines.isEmpty()) BigDecimal.ZERO else cfg.deliveryFeeEgp
        return OrderBreakdown(
            lines = lines,
            sarTotal = lines.fold(BigDecimal.ZERO) { a, l -> a + l.sarTotal },
            convertedEgp = lines.fold(BigDecimal.ZERO) { a, l -> a + l.convertedEgp },
            extraEgp = lines.fold(BigDecimal.ZERO) { a, l -> a + l.extraEgp },
            subtotalEgp = subtotal,
            deliveryFeeEgp = fee,
            totalEgp = subtotal + fee,
        )
    }
}

object Money {
    private val fmt = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))
    fun plain(v: BigDecimal): String = fmt.format(v)
    fun plain(v: Double): String = fmt.format(v)
    fun egp(v: BigDecimal): String = "${plain(v)} ج.م"
    fun egp(v: Double): String = "${plain(v)} ج.م"
    fun sar(v: BigDecimal): String = "${plain(v)} ر.س"
    fun sar(v: Double): String = "${plain(v)} ر.س"
}
