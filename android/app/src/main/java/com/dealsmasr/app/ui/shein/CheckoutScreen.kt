package com.dealsmasr.app.ui.shein

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.dealsmasr.app.ui.i18n.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dealsmasr.app.core.SheinPricingConfig
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.components.userMessage
import kotlinx.coroutines.launch

private val EG_PHONE = Regex("""^(\+?20|0)?1[0125]\d{8}$""")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutScreen(onBack: () -> Unit, onPlaced: (String) -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val cart by c.shein.cart.collectAsStateWithLifecycle(emptyList())
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(settings != null) {
        settings?.let { if (name.isEmpty()) { name = it.customerName; phone = it.customerPhone; address = it.customerAddress } }
    }
    val cfg = settings?.pricing ?: SheinPricingConfig()
    val b = c.shein.breakdown(cart, cfg)
    val phoneOk = EG_PHONE.matches(phone.replace(" ", ""))

    Scaffold(topBar = {
        TopAppBar(title = { Text("إتمام طلب SHEIN") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع") } })
    }) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("الاسم") }, singleLine = true)
            OutlinedTextField(phone, { phone = it.filter { ch -> ch.isDigit() || ch == '+' || ch == ' ' } }, Modifier.fillMaxWidth(),
                label = { Text("رقم الموبايل") }, singleLine = true, isError = phone.isNotEmpty() && !phoneOk,
                supportingText = { if (phone.isNotEmpty() && !phoneOk) Text("اكتب رقم موبايل مصري صحيح (01xxxxxxxxx)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("العنوان بالتفصيل") }, minLines = 2)
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), label = { Text("ملاحظات على الطلب (اختياري)") },
                placeholder = { Text("مثلاً: المقاس، اللون، ميعاد مناسب للتوصيل…") }, minLines = 2)
            Card(shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${cart.size} منتج", style = MaterialTheme.typography.titleMedium)
                    if (b != null) BreakdownRows(b.sarTotal, b.convertedEgp, b.extraEgp, b.subtotalEgp, b.deliveryFeeEgp, b.totalEgp, cfg)
                    else Text("السلة غير جاهزة", color = MaterialTheme.colorScheme.error)
                }
            }
            Text("بعد التأكيد هيتفتح واتساب برسالة الطلب لفريقنا — اضغط إرسال. التوصيل خلال 25–${settings?.countdownDays ?: 35} يوم من تأكيد الطلب.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        runCatching { c.shein.submitOrder(name.trim(), phone.replace(" ", ""), address.trim(), notes.trim()) }
                            .onSuccess { order ->
                                val images = c.shein.itemsOf(order).mapNotNull { it.localImagePath }
                                // Images already uploaded to the backend are visible to the admin; attach only local-mode images.
                                WhatsApp.sendOrder(ctx, order.whatsappText, settings?.whatsappNumber.orEmpty(),
                                    if (order.synced) emptyList() else images)
                                onPlaced(order.id)
                            }
                            .onFailure { error = it.userMessage() }
                        busy = false
                    }
                },
                enabled = !busy && b != null && name.isNotBlank() && phoneOk,
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) { Text(if (busy) "جاري الإرسال…" else "تأكيد الطلب") }
        }
    }
}
