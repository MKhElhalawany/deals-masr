package com.dealsmasr.app.ui.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.data.auth.CloudOrder
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.orders.STATUS_AR
import com.dealsmasr.app.ui.product.openUrl
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val NEXT = listOf("confirmed", "purchased", "shipped", "delivered")
private val fmt = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm", Locale.US)

/** Shop owner's order desk: every customer order, confirm with one tap (the customer sees it instantly on next sync). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminOrdersScreen(onBack: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf<String?>("submitted") }
    var orders by remember { mutableStateOf<List<CloudOrder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var busyId by remember { mutableStateOf<String?>(null) }

    fun load() = scope.launch {
        loading = true; error = null
        runCatching { c.admin.orders(filter) }.onSuccess { orders = it }.onFailure { error = it.message }
        loading = false
    }
    LaunchedEffect(filter) { load() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("إدارة الطلبات") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        Column(Modifier.padding(pad)) {
            LazyRow(contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("الكل") }) }
                items(STATUS_AR.keys.toList()) { st ->
                    FilterChip(selected = filter == st, onClick = { filter = st }, label = { Text(STATUS_AR[st] ?: st) })
                }
            }
            PullToRefreshBox(isRefreshing = loading, onRefresh = { load() }, modifier = Modifier.fillMaxSize()) {
                val list = orders
                when {
                    error != null && list == null -> Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
                    list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    list.isEmpty() -> Text("مفيش طلبات هنا", Modifier.padding(24.dp))
                    else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(list, key = { it.id }) { o ->
                            OrderCard(o, busy = busyId == o.id,
                                onStatus = { st ->
                                    busyId = o.id
                                    scope.launch {
                                        runCatching { c.admin.setStatus(o.id, st) }
                                            .onSuccess { upd -> orders = orders?.map { if (it.id == o.id && upd != null) upd else it } }
                                            .onFailure { error = it.message }
                                        busyId = null
                                    }
                                },
                                onWhatsApp = { openUrl(ctx, "https://wa.me/${o.customerPhone.filter(Char::isDigit).let { if (it.startsWith("0")) "2$it" else it }}") },
                                onOpen = { openUrl(ctx, it) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrderCard(o: CloudOrder, busy: Boolean, onStatus: (String) -> Unit, onWhatsApp: () -> Unit, onOpen: (String) -> Unit) {
    val created = runCatching { fmt.format(OffsetDateTime.parse(o.createdAt).atZoneSameInstant(ZoneId.of("Africa/Cairo"))) }.getOrDefault(o.createdAt)
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${o.id}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(STATUS_AR[o.status] ?: o.status, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Text("${o.customerName} · ${o.customerPhone}", fontWeight = FontWeight.SemiBold)
            o.address?.let { Text("📍 $it", style = MaterialTheme.typography.bodySmall) }
            o.notes?.let { Text("📝 $it", style = MaterialTheme.typography.bodySmall) }
            Text("🗓️ $created", style = MaterialTheme.typography.labelSmall)
            HorizontalDivider()
            o.items.forEach { i ->
                Column(Modifier.fillMaxWidth().clickable(enabled = i.productUrl != null) { i.productUrl?.let(onOpen) }) {
                    Text(i.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    Text(listOfNotNull(i.size.ifBlank { null }?.let { "مقاس $it" }, i.color.ifBlank { null }?.let { "لون $it" },
                        "كمية ${i.quantity}", i.unitSar.ifBlank { null }?.let { "$it ر.س" },
                        i.lineTotalEgp.ifBlank { null }?.let { "${it} ج.م" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (i.notes.isNotBlank()) Text(i.notes, style = MaterialTheme.typography.labelSmall)
                    i.productUrl?.let { Text("فتح اللينك ↗", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall) }
                }
            }
            HorizontalDivider()
            Text("الإجمالي: ${Money.egp(o.totalEgp)} (منتجات ${Money.egp(o.subtotalEgp)} + توصيل ${Money.egp(o.deliveryFeeEgp)})",
                fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val next = when (o.status) {
                    "submitted" -> "confirmed"
                    in NEXT -> NEXT.getOrNull(NEXT.indexOf(o.status) + 1)
                    else -> null
                }
                if (next != null) {
                    Button(onClick = { onStatus(next) }, enabled = !busy,
                        colors = if (next == "confirmed") ButtonDefaults.buttonColors(containerColor = Color(0xFF15803D)) else ButtonDefaults.buttonColors()) {
                        Text(if (next == "confirmed") "✓ تم التأكيد" else "← ${STATUS_AR[next]}")
                    }
                }
                OutlinedButton(onClick = onWhatsApp) { Text("واتساب") }
                if (o.status != "cancelled" && o.status != "delivered") {
                    OutlinedButton(onClick = { onStatus("cancelled") }, enabled = !busy) { Text("إلغاء") }
                }
            }
        }
    }
}
