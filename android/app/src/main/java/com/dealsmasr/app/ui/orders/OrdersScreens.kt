package com.dealsmasr.app.ui.orders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.core.OrderCountdown
import com.dealsmasr.app.data.PricingSnapshot
import com.dealsmasr.app.data.local.OrderEntity
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.components.MessageState
import com.dealsmasr.app.ui.components.NetImage
import com.dealsmasr.app.ui.product.openUrl
import com.dealsmasr.app.ui.shein.BreakdownRows
import com.dealsmasr.app.ui.shein.WhatsApp
import com.dealsmasr.app.ui.theme.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

val STATUS_AR = mapOf(
    "submitted" to "بانتظار التأكيد",
    "confirmed" to "تم التأكيد",
    "purchased" to "تم الشراء من SHEIN",
    "shipped" to "في الشحن",
    "delivered" to "تم التوصيل",
    "cancelled" to "ملغي",
)
private val STATUS_FLOW = listOf("submitted", "confirmed", "purchased", "shipped", "delivered")

/** Current time that ticks every minute so countdowns update while the screen is open. */
@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }
    return now
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(onOrder: (String) -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val orders by c.shein.orders.collectAsStateWithLifecycle(null)
    var syncing by remember { mutableStateOf(false) }
    val now = rememberNow()
    LaunchedEffect(Unit) { runCatching { c.shein.syncOrders() } }

    Scaffold(topBar = { TopAppBar(title = { Text("طلباتي") }) }) { pad ->
        PullToRefreshBox(isRefreshing = syncing, onRefresh = {
            scope.launch { syncing = true; runCatching { c.shein.syncOrders() }; syncing = false }
        }, modifier = Modifier.padding(pad).fillMaxSize()) {
            val list = orders
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                list.isEmpty() -> MessageState(Icons.Outlined.Inventory2, "مفيش طلبات لسه",
                    "طلبات SHEIN اللي بتعملها هتظهر هنا مع العد التنازلي لوصولها")
                else -> LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.id }) { o -> OrderRow(o, now) { onOrder(o.id) } }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val color = when (status) {
        "cancelled" -> MaterialTheme.colorScheme.error
        "delivered" -> Success
        "submitted" -> Color(0xFFF57C00)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Text(STATUS_AR[status] ?: status, color = color, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
private fun OrderRow(o: OrderEntity, now: Long, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("طلب #${o.id}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(o.status)
            }
            Text("الإجمالي: ${Money.egp(BigDecimal(o.totalEgp))}", fontWeight = FontWeight.SemiBold)
            val confirmed = o.confirmedAt
            if (confirmed != null && o.status !in setOf("delivered", "cancelled")) {
                val left = OrderCountdown.daysRemaining(confirmed, now, o.countdownDays)
                Text(OrderCountdown.remainingText(left), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                LinearProgressIndicator(progress = { OrderCountdown.progress(left, o.countdownDays) }, modifier = Modifier.fillMaxWidth())
            } else if (o.status == "submitted") {
                Text("العد التنازلي (${o.countdownDays} يوم) يبدأ بعد تأكيد الطلب", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(id: String, onBack: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val order by c.shein.order(id).collectAsStateWithLifecycle(null)
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val now = rememberNow()
    LaunchedEffect(id) { runCatching { c.shein.syncOrders() } }

    Scaffold(topBar = {
        TopAppBar(title = { Text("طلب #$id") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        val o = order ?: return@Scaffold
        val items = remember(o.itemsJson) { c.shein.itemsOf(o) }
        val pricing = remember(o.pricingJson) {
            runCatching { c.api.json.decodeFromString(PricingSnapshot.serializer(), o.pricingJson) }.getOrNull()
        }
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CountdownCard(o, now)
            StatusTimeline(o.status)
            Text("المنتجات", style = MaterialTheme.typography.titleMedium)
            items.forEach { it ->
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().clickable(enabled = it.productUrl != null) {
                    it.productUrl?.let { u -> openUrl(ctx, u) }
                }) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val img = it.imageUrl ?: it.localImagePath?.let { p -> File(p).toURI().toString() }
                        NetImage(img, Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)))
                        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                            Text(it.title, maxLines = 2, style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(it.size.ifBlank { null }?.let { s -> "المقاس $s" }, it.color.ifBlank { null }?.let { s -> "اللون $s" },
                                "الكمية ${it.quantity}").joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                            if (it.notes.isNotBlank()) Text(it.notes, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(Money.egp(BigDecimal(it.lineTotalEgp)), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cfg = com.dealsmasr.app.core.SheinPricingConfig.of(pricing?.sarToEgp, pricing?.multiplier, pricing?.deliveryFeeEgp)
                    BreakdownRows(BigDecimal(o.sarTotal), BigDecimal(o.convertedEgp), BigDecimal(o.extraEgp), BigDecimal(o.subtotalEgp),
                        BigDecimal(o.deliveryFeeEgp), BigDecimal(o.totalEgp), cfg)
                }
            }
            val created = java.time.Instant.ofEpochMilli(o.createdAt).atZone(OrderCountdown.CAIRO)
            Text("تاريخ الطلب: ${DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US).format(created)}",
                style = MaterialTheme.typography.bodySmall)
            if (!o.synced && settings?.hasServer == true) {
                Text("الطلب لسه ما اتبعتش للخادم — هنحاول تلقائياً.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = {
                WhatsApp.sendOrder(ctx, o.whatsappText, settings?.whatsappNumber.orEmpty(),
                    if (o.synced) emptyList() else items.mapNotNull { it.localImagePath })
            }, modifier = Modifier.fillMaxWidth()) { Text("إعادة إرسال الطلب على واتساب") }
        }
    }
}

@Composable
private fun CountdownCard(o: OrderEntity, now: Long) {
    Card(shape = RoundedCornerShape(24.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val confirmed = o.confirmedAt
            when {
                o.status == "cancelled" -> Text("تم إلغاء الطلب", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                o.status == "delivered" -> Text("تم توصيل طلبك 🎉", style = MaterialTheme.typography.titleLarge, color = Success)
                confirmed == null -> {
                    Text("بانتظار تأكيد الطلب", style = MaterialTheme.typography.titleLarge)
                    Text("العد التنازلي ${o.countdownDays} يوم هيبدأ أول ما نأكد طلبك", textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> {
                    val left = OrderCountdown.daysRemaining(confirmed, now, o.countdownDays)
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(150.dp)) {
                        CircularProgressIndicator(progress = { OrderCountdown.progress(left, o.countdownDays) },
                            modifier = Modifier.size(150.dp), strokeWidth = 10.dp,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant)
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("$left", fontSize = 48.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                            Text(if (left in 3..10) "أيام" else "يوم", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Text(OrderCountdown.remainingText(left), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                    Text("الوصول المتوقع: ${OrderCountdown.expectedDate(confirmed, o.countdownDays)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun StatusTimeline(status: String) {
    if (status == "cancelled") return
    val idx = STATUS_FLOW.indexOf(status)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        STATUS_FLOW.forEachIndexed { i, st ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Surface(shape = RoundedCornerShape(50), color = if (i <= idx) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(14.dp)) {}
                Text(STATUS_AR[st] ?: st, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
                    color = if (i <= idx) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    HorizontalDivider()
}
