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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.dealsmasr.app.data.auth.AuthState
import com.dealsmasr.app.data.remote.MarketplaceDto
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.components.relativeTime
import com.dealsmasr.app.ui.components.userMessage
import com.dealsmasr.app.work.Notifier
import kotlinx.coroutines.launch
import java.math.BigDecimal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onAdminOrders: () -> Unit = {}) {
    val c = appContainer()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val s by c.settings.settings.collectAsStateWithLifecycle(null)
    val alerts by c.deals.alerts.collectAsStateWithLifecycle(emptyList())
    val authState by c.auth.state.collectAsStateWithLifecycle()
    var health by remember { mutableStateOf<List<MarketplaceDto>?>(null) }
    var healthError by remember { mutableStateOf<String?>(null) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scope.launch { c.settings.setNotifications(granted) }
    }
    val isAdmin = (authState as? AuthState.SignedIn)?.profile?.isAdmin == true
    fun loadHealth() = scope.launch {
        runCatching { c.deals.marketplaces() }.onSuccess { health = it; healthError = null }.onFailure { healthError = it.userMessage() }
    }
    LaunchedEffect(isAdmin) { if (isAdmin) loadHealth() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("الإعدادات") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        val st = s ?: return@Scaffold
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            (authState as? AuthState.SignedIn)?.let { a ->
                Section("حسابي") {
                    Text(a.profile.fullName ?: "—", fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(a.email, a.profile.phone).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    if (a.profile.isAdmin) Button(onClick = onAdminOrders, modifier = Modifier.fillMaxWidth()) { Text("إدارة الطلبات (أدمن)") }
                    OutlinedButton(onClick = { scope.launch { c.auth.signOut() } }) { Text("تسجيل الخروج") }
                }
            }
            Section("الإشعارات") {
                Toggle("نبهني بالعروض الجديدة", st.notificationsEnabled) { on ->
                    if (on && Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(ctx)) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else scope.launch { c.settings.setNotifications(on) }
                }
                Text("ابعتلي إشعار من خصم:", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(10, 30, 50, 70).forEach { p ->
                        FilterChip(selected = st.notifyMinPct == p, onClick = { scope.launch { c.settings.setNotifyMinPct(p) } },
                            label = { Text("$p%+") })
                    }
                }
                Text("العروض بيتابعها سيرفرنا 24 ساعة. 10–29% إشعار هادي · 30%+ 🔥 · 50%+ ⚡ · 70%+ 🚨 «الحق!»",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val live by c.realtime.connected.collectAsState()
                Text(
                    (if (live) "🟢 متصل مباشرة — العروض بتظهر أول ما السيرفر يلاقيها" else "⚪ الاتصال المباشر مقفول — بنحدّث كل دقيقة") + "\n" +
                        (if (com.dealsmasr.app.push.Push.configured) "🔔 إشعارات فورية حتى والتطبيق مقفول" else "🔕 الإشعارات الفورية والتطبيق مقفول لسه مش متفعّلة (بنشيّك كل 15 دقيقة)"),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("ساعد في تحديث العروض") {
                Toggle("خلي موبايلي يساعد في قراية عروض جوميا (واي فاي بس، والأبليكيشن مفتوح)", st.phoneAssist) { on ->
                    scope.launch { c.settings.setPhoneAssist(on) }
                }
                Text("جوميا بتحجب السيرفرات. الموبايل بيقرا صفحة واحدة كل دقيقة على الأكتر من قايمة السيرفر، ومابيستهلكش باقة الموبايل.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Section("المظهر") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ThemeMode.SYSTEM to "النظام", ThemeMode.LIGHT to "فاتح", ThemeMode.DARK to "داكن").forEach { (m, l) ->
                        FilterChip(selected = st.theme == m, onClick = { scope.launch { c.settings.setTheme(m) } }, label = { Text(l) })
                    }
                }
            }
            Section("تنبيهات الأسعار") {
                if (alerts.isEmpty()) Text("مفيش تنبيهات. اعمل تنبيه من شاشة البحث — السيرفر هيبلغك حتى لو الموبايل مقفول.",
                    style = MaterialTheme.typography.bodySmall)
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
            if (isAdmin) Section("أدمن: حالة سيرفر العروض") {
                healthError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (health?.isEmpty() == true) Text("السيرفر لسه ما اشتغلش — أول ما يشتغل هتظهر حالته هنا.")
                health?.forEach { m ->
                    Column {
                        Text(m.nameAr + " · " + relativeTime(m.lastRunAt ?: 0), fontWeight = FontWeight.SemiBold)
                        Text(m.status, style = MaterialTheme.typography.labelMedium,
                            color = if (m.configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    }
                }
                OutlinedButton(onClick = { loadHealth() }) { Text("تحديث الحالة") }
            }
            if (isAdmin) Section("أدمن: معادلة تسعير SHEIN") {
                val p = st.pricing
                Text("(السعر الأصلي بالريال × ${p.sarToEgp.toPlainString()} × ${p.multiplier.toPlainString()}) + ${Money.egp(p.deliveryFeeEgp)} توصيل لكل طلب")
                val ex = BigDecimal(100).multiply(p.sarToEgp).multiply(p.multiplier).add(p.deliveryFeeEgp)
                Text("مثال: منتج بـ 100 ر.س = ${Money.egp(ex)}", style = MaterialTheme.typography.bodySmall)
                Text("بتتغير من Supabase ← app_config.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
