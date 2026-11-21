package com.dealsmasr.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.data.ThemeMode
import com.dealsmasr.app.data.remote.MarketplaceDto
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.components.userMessage
import com.dealsmasr.app.work.Notifier
import kotlinx.coroutines.launch
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val s by c.settings.settings.collectAsStateWithLifecycle(null)
    val alerts by c.deals.alerts.collectAsStateWithLifecycle(emptyList())
    var url by remember { mutableStateOf<String?>(null) }
    var markets by remember { mutableStateOf<List<MarketplaceDto>?>(null) }
    var testMsg by remember { mutableStateOf<String?>(null) }
    var keywords by remember { mutableStateOf<String?>(null) }
    var refreshing by remember { mutableStateOf(false) }
    var refreshMsg by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { c.settings.setNotifications(granted) }
    }

    LaunchedEffect(s?.serverUrl) { if (url == null && s != null) url = s!!.serverUrl }

    fun test() = scope.launch {
        testMsg = "جاري الاختبار…"
        c.settings.setServerUrl(url.orEmpty())
        runCatching { c.deals.marketplaces() }
            .onSuccess { markets = it; testMsg = "تم الاتصال ✓"; runCatching { c.deals.refresh() } }
            .onFailure { markets = null; testMsg = it.userMessage() }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("الإعدادات") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        val st = s ?: return@Scaffold
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!st.hasServer) Section("القراءة المباشرة من المواقع") {
                Text("التطبيق بيقرا صفحات العروض من المواقع كل 10 دقايق وهو مفتوح، وكل 15 دقيقة في الخلفية.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOf("amazon" to "أمازون مصر", "jumia" to "جوميا مصر").forEach { (id, name) ->
                    Toggle(name, id in st.scrapeStores) { on ->
                        scope.launch { c.settings.setScrapeStores(if (on) st.scrapeStores + id else st.scrapeStores - id) }
                    }
                    Text(st.scrapeStatus[id] ?: "لسه ماتقراش", style = MaterialTheme.typography.labelMedium,
                        color = if ((st.scrapeStatus[id] ?: "").startsWith("✓")) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("نون: غير مدعوم حالياً (صفحاته بتتحمّل بالجافاسكريبت).", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(keywords ?: st.scrapeKeywords.joinToString("\n"), { keywords = it }, Modifier.fillMaxWidth(),
                    label = { Text("كلمات البحث عن العروض (كلمة في كل سطر)") }, minLines = 4, maxLines = 8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !refreshing, onClick = {
                        scope.launch {
                            keywords?.let { c.settings.setScrapeKeywords(it) }
                            refreshing = true
                            refreshMsg = runCatching { c.deals.refresh() }
                                .fold({ "تم ✓ — ${it.size} عرض جديد" }, { it.userMessage() })
                            refreshing = false
                        }
                    }) { Text(if (refreshing) "جاري القراءة…" else "حفظ وتحديث الآن") }
                }
                refreshMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            Section("خادم العروض (اختياري)") {
                OutlinedTextField(url.orEmpty(), { url = it }, Modifier.fillMaxWidth(), label = { Text("عنوان الخادم") },
                    placeholder = { Text("https://api.example.com") }, singleLine = true)
                Button(onClick = { test() }) { Text("حفظ واختبار الاتصال") }
                testMsg?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                markets?.forEach { m ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(m.nameAr, Modifier.weight(1f))
                        Text(if (m.configured && m.enabled) "متصل ✓" else if (!m.enabled) "متوقف" else "بانتظار بيانات الاعتماد",
                            color = if (m.configured && m.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text("سيبه فاضي علشان التطبيق يقرا العروض من المواقع مباشرة. لو حطيت سيرفر، العروض هتيجي منه بدل كده.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("المظهر") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "النظام", ThemeMode.LIGHT to "فاتح", ThemeMode.DARK to "داكن").forEach { (m, l) ->
                        FilterChip(selected = st.theme == m, onClick = { scope.launch { c.settings.setTheme(m) } }, label = { Text(l) })
                    }
                }
            }
            Section("الإشعارات") {
                Toggle("تفعيل الإشعارات", st.notificationsEnabled) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(ctx)) {
                        permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) // result callback stores the choice
                    } else {
                        scope.launch { c.settings.setNotifications(on) }
                    }
                }
                Toggle("تنبيه بالعروض الكبيرة (خصم 50%+)", st.majorDealAlerts) { on -> scope.launch { c.settings.setMajorDeals(on) } }
            }
            Section("تنبيهات الأسعار") {
                if (alerts.isEmpty()) Text("مفيش تنبيهات. اعمل تنبيه من شاشة البحث.", style = MaterialTheme.typography.bodySmall)
                alerts.forEach { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(a.query, fontWeight = FontWeight.SemiBold)
                            Text("خصم ${a.minDiscount}%+" + (a.maxPrice?.let { " · حتى ${Money.egp(it)}" } ?: ""),
                                style = MaterialTheme.typography.labelMedium)
                        }
                        IconButton(onClick = { scope.launch { c.deals.deleteAlert(a) } }) { Icon(Icons.Outlined.Delete, "حذف") }
                    }
                }
            }
            Section("معادلة تسعير SHEIN") {
                val p = st.pricing
                Text("(السعر الأصلي بالريال × ${p.sarToEgp.toPlainString()} × ${p.multiplier.toPlainString()}) + ${Money.egp(p.deliveryFeeEgp)} توصيل لكل طلب")
                val ex = BigDecimal(100).multiply(p.sarToEgp).multiply(p.multiplier).add(p.deliveryFeeEgp)
                Text("مثال: منتج بـ 100 ر.س = ${Money.egp(ex)}", style = MaterialTheme.typography.bodySmall)
                Text("القيم بتتحدد من لوحة التحكم.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
