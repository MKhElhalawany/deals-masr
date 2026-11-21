package com.dealsmasr.app.core

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SummaryItem(
    val title: String,
    val productUrl: String?,
    val imageUrl: String?,
    val size: String?,
    val color: String?,
    val notes: String?,
    val quantity: Int,
    val line: LineBreakdown,
)

data class SummaryOrder(
    val id: String,
    val customerName: String,
    val customerPhone: String,
    val address: String?,
    val items: List<SummaryItem>,
    val breakdown: OrderBreakdown,
    val createdAtMillis: Long,
)

/** Builds the WhatsApp order message (Arabic). */
object OrderSummary {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US)

    fun whatsappText(o: SummaryOrder): String = buildString {
        appendLine("🛍️ طلب SHEIN جديد #${o.id}")
        appendLine("👤 الاسم: ${o.customerName}")
        appendLine("📞 الموبايل: ${o.customerPhone}")
        if (!o.address.isNullOrBlank()) appendLine("📍 العنوان: ${o.address}")
        appendLine("🗓️ تاريخ الطلب: ${dateFmt.format(Instant.ofEpochMilli(o.createdAtMillis).atZone(OrderCountdown.CAIRO))}")
        appendLine("————————")
        o.items.forEachIndexed { i, it ->
            appendLine("${i + 1}) ${it.title}")
            it.productUrl?.takeIf { u -> u.isNotBlank() }?.let { u -> appendLine("🔗 $u") }
            it.imageUrl?.takeIf { u -> u.isNotBlank() }?.let { u -> appendLine("🖼️ $u") }
            val attrs = listOfNotNull(
                it.size?.takeIf { s -> s.isNotBlank() }?.let { s -> "المقاس: $s" },
                it.color?.takeIf { s -> s.isNotBlank() }?.let { s -> "اللون: $s" },
                "الكمية: ${it.quantity}",
            )
            appendLine(attrs.joinToString(" | "))
            it.notes?.takeIf { s -> s.isNotBlank() }?.let { s -> appendLine("📝 $s") }
            appendLine("السعر: ${Money.sar(it.line.sarUnit)} × ${it.quantity} = ${Money.egp(it.line.lineTotalEgp)}")
            appendLine()
        }
        appendLine("————————")
        appendLine("إجمالي المنتجات: ${Money.egp(o.breakdown.subtotalEgp)}")
        appendLine("التوصيل: ${Money.egp(o.breakdown.deliveryFeeEgp)}")
        append("💰 الإجمالي المطلوب: ${Money.egp(o.breakdown.totalEgp)}")
    }
}
