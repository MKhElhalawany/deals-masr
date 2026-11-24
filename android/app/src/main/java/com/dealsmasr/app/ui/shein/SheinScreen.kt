package com.dealsmasr.app.ui.shein

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.LocalShipping
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import com.dealsmasr.app.ui.i18n.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.core.SheinPricing
import com.dealsmasr.app.core.SheinPricingConfig
import com.dealsmasr.app.data.AddResult
import com.dealsmasr.app.data.AppSettings
import com.dealsmasr.app.data.SettingsStore
import com.dealsmasr.app.data.SheinRepository
import com.dealsmasr.app.data.local.CartItemEntity
import com.dealsmasr.app.data.local.displayTitle
import com.dealsmasr.app.data.local.isPriced
import com.dealsmasr.app.data.remote.CandidateDto
import com.dealsmasr.app.ui.appViewModel
import com.dealsmasr.app.ui.components.NetImage
import com.dealsmasr.app.ui.components.userMessage
import com.dealsmasr.app.ui.product.openUrl
import com.dealsmasr.app.ui.theme.SheinBlack
import com.dealsmasr.app.ui.theme.Success
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigDecimal

data class SheinState(val cart: List<CartItemEntity> = emptyList(), val settings: AppSettings? = null)

class SheinViewModel(private val repo: SheinRepository, settings: SettingsStore) : ViewModel() {
    val state: StateFlow<SheinState> = combine(repo.cart, settings.settings) { c, s -> SheinState(c, s) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SheinState())
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    var adding by mutableStateOf(false)
        private set

    fun add(mode: Int, text: String, manualPrice: Double?, onDone: () -> Unit) = viewModelScope.launch {
        adding = true
        val r = when (mode) {
            0 -> repo.addByUrl(text, manualPrice)
            1 -> repo.addBag(text)
            else -> repo.addByText(text, manualPrice)
        }
        adding = false
        report(r, onDone)
    }

    fun addImage(uri: Uri, manualPrice: Double?, onDone: () -> Unit) = viewModelScope.launch {
        adding = true
        val r = repo.addByImage(uri, manualPrice)
        adding = false
        report(r, onDone)
    }

    private fun report(r: AddResult, onDone: () -> Unit) {
        when (r) {
            AddResult.AddedPriced -> { messages.tryEmit("تمت الإضافة للسلة ✓"); onDone() }
            AddResult.Added -> { messages.tryEmit("تمت الإضافة — بنجيب السعر من SHEIN السعودية…"); onDone() }
            is AddResult.Error -> messages.tryEmit(r.message)
        }
    }

    fun refreshQuotes(silent: Boolean = true) = viewModelScope.launch {
        runCatching { repo.refreshQuotes() }.onFailure { if (!silent) messages.tryEmit(it.userMessage()) }
    }

    fun update(item: CartItemEntity) = viewModelScope.launch { repo.update(item) }
    fun remove(item: CartItemEntity) = viewModelScope.launch { repo.remove(item) }
    fun candidates(item: CartItemEntity) = repo.candidates(item)
    fun choose(item: CartItemEntity, index: Int) = viewModelScope.launch {
        runCatching { repo.choose(item, index) }.onFailure { messages.tryEmit(it.userMessage()) }
    }
    fun breakdown(items: List<CartItemEntity>, cfg: SheinPricingConfig) = repo.breakdown(items, cfg)
    fun retry(item: CartItemEntity) = repo.retry(item)
}

const val SHEIN_SLOGAN = "عروض مصر بتوفرلك منتجات شي إن لحد باب بيتك · 0 جمارك · 0 مخاطرة · أسرع وقت · خصومات لحد 80%"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheinScreen(sharedText: String?, onSharedConsumed: () -> Unit, onCheckout: () -> Unit, onOrders: () -> Unit = {}) {
    val vm = appViewModel { SheinViewModel(it.shein, it.settings) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    var mode by remember { mutableStateOf(0) } // 0 link, 1 bag, 2 image, 3 name
    val admin = s.settings?.adminMode == true
    var input by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var pickedImage by remember { mutableStateOf<Uri?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) pickedImage = uri }
    val reset = { input = ""; price = ""; pickedImage = null }

    LaunchedEffect(sharedText) {
        if (!sharedText.isNullOrBlank()) { mode = 0; input = sharedText; onSharedConsumed() }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { while (true) { vm.refreshQuotes(); delay(30_000) } }

    val cfg = s.settings?.pricing ?: SheinPricingConfig()
    val breakdown = vm.breakdown(s.cart, cfg)

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { SheinHero() }
            item {
                androidx.compose.material3.OutlinedButton(onClick = onOrders, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("📦 طلباتي ومتابعة الشحن")
                }
            }
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("أضف منتج من SHEIN", style = MaterialTheme.typography.titleMedium)
                        val labels = listOf("لينك منتج", "باج كاملة", "صورة", "اسم")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            labels.forEachIndexed { i, l ->
                                SegmentedButton(selected = mode == i, onClick = { mode = i },
                                    shape = SegmentedButtonDefaults.itemShape(i, labels.size)) { Text(l) }
                            }
                        }
                        when (mode) {
                            0, 1 -> OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text(if (mode == 0) "الصق لينك المنتج من تطبيق أو موقع SHEIN" else "الصق لينك مشاركة الباج من SHEIN") },
                                singleLine = true,
                                supportingText = {
                                    Text(if (mode == 0) "هنفتح المنتج على SHEIN السعودية ونحسب السعر على أعلى مقاس"
                                    else "هنقرا كل منتجات الباج ونسعّر كل واحد على أعلى سعر")
                                },
                                trailingIcon = {
                                    IconButton(onClick = { clipboard.getText()?.text?.let { input = it } }) { Icon(Icons.Outlined.ContentPaste, "لصق") }
                                })
                            2 -> Surface(onClick = {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth().height(140.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (pickedImage != null) NetImage(pickedImage.toString(), Modifier.fillMaxWidth().height(140.dp))
                                    else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(40.dp))
                                        Text("اختار صورة المنتج (سكرين شوت)")
                                    }
                                }
                            }
                            else -> OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
                                placeholder = { Text("اكتب اسم المنتج أو كلمات تصفه") })
                        }
                        if (mode >= 2) Text("المنتجات اللي بالصورة أو بالاسم فريقنا بيدوّر عليها ويبعتلك سعرها على واتساب.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (admin && mode != 1) OutlinedTextField(price, { v -> price = v.filter { it.isDigit() || it == '.' } }, Modifier.fillMaxWidth(),
                            label = { Text("أدمن: سعر SHEIN السعودية الأصلي (اختياري)") }, suffix = { Text("ر.س") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        Button(
                            onClick = {
                                val p = price.toDoubleOrNull()?.takeIf { it > 0 }
                                when (mode) {
                                    2 -> pickedImage?.let { vm.addImage(it, p, reset) }
                                    3 -> vm.add(3, input, p, reset)
                                    else -> vm.add(mode, input, p, reset)
                                }
                            },
                            enabled = !vm.adding && (if (mode == 2) pickedImage != null else input.isNotBlank()),
                            modifier = Modifier.fillMaxWidth().height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SheinBlack, contentColor = Color.White),
                        ) { Text(if (vm.adding) "جاري الإضافة…" else "أضف للسلة") }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("سلة SHEIN (${s.cart.size})", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (s.cart.any { it.quoteStatus == "pending" || it.quoteStatus == "needs_choice" } && s.settings?.hasServer == true) {
                        TextButton(onClick = { vm.refreshQuotes(silent = false) }) { Icon(Icons.Outlined.Refresh, null); Text(" تحديث") }
                    }
                }
            }
            if (s.cart.isEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.ShoppingBag, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("السلة فاضية — الصق رابط أي منتج من SHEIN", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(s.cart, key = { it.id }) { item ->
                CartItemCard(item, cfg, vm, admin, onOpen = { url -> openUrl(ctx, url) },
                    onTeam = { WhatsApp.askTeam(ctx, item, s.settings?.whatsappNumber ?: com.dealsmasr.app.data.OWNER_WHATSAPP) })
            }
            if (s.cart.isNotEmpty()) {
                item {
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("ملخص الطلب", style = MaterialTheme.typography.titleMedium)
                            if (breakdown == null) {
                                Text("بعض المنتجات لسه بتتسعّر أو محتاجة فريقنا. هتقدر تكمل الطلب أول ما كل الأسعار تبقى جاهزة (أو امسح المنتج اللي مش متسعّر).",
                                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            } else {
                                BreakdownRows(breakdown.sarTotal, breakdown.convertedEgp, breakdown.extraEgp, breakdown.subtotalEgp,
                                    breakdown.deliveryFeeEgp, breakdown.totalEgp, cfg, detailed = admin)
                            }
                            Spacer(Modifier.height(6.dp))
                            Button(onClick = onCheckout, enabled = breakdown != null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                                Text("إتمام الطلب")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheinHero() {
    Box(
        Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(SheinBlack, Color(0xFF3A3A3A))))
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("SHEIN", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(SHEIN_SLOGAN, color = Color.White, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Trust(Icons.Outlined.Verified, "أسعار شفافة")
                Trust(Icons.Outlined.LocalShipping, "لحد باب البيت")
                Trust(Icons.Outlined.ShoppingBag, "متابعة الطلب")
            }
        }
    }
}

@Composable
private fun Trust(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color(0xFFFFD54F), modifier = Modifier.size(16.dp))
        Text(" $label", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun BreakdownRows(
    sar: BigDecimal, converted: BigDecimal, extra: BigDecimal, subtotal: BigDecimal, fee: BigDecimal, total: BigDecimal,
    cfg: SheinPricingConfig,
    detailed: Boolean = false,
) {
    if (detailed) {
        val extraPct = cfg.multiplier.subtract(BigDecimal.ONE).multiply(BigDecimal(100)).stripTrailingZeros().toPlainString()
        Line("أدمن: سعر SHEIN الأصلي", Money.sar(sar))
        Line("أدمن: بعد التحويل (× ${cfg.sarToEgp.toPlainString()})", Money.egp(converted))
        Line("أدمن: إضافة $extraPct%", Money.egp(extra))
    }
    if (fee.signum() != 0) {
        Line("سعر المنتجات", Money.egp(subtotal))
        Line("التوصيل لحد باب البيت", Money.egp(fee))
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
    }
    Line("هنوصلهولك لحد باب بيتك بـ", Money.egp(total), bold = true)
}

@Composable
private fun Line(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f), style = if (bold) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Medium,
            color = if (bold) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun StatusChip(item: CartItemEntity) {
    val (label, color) = when {
        item.quoteStatus == "pricing" -> "⏳ بنجيب السعر من SHEIN السعودية…" to MaterialTheme.colorScheme.onSurfaceVariant
        item.quoteStatus == "auto_failed" -> "تعذر التسعير التلقائي" to MaterialTheme.colorScheme.error
        item.quoteStatus == "team" -> "فريقنا هيسعّره لك على واتساب" to Color(0xFF0277BD)
        item.priceSource == "auto" -> "تم التسعير ✓" to Success
        item.quoteStatus == "not_found" -> "غير متوفر" to MaterialTheme.colorScheme.error
        item.quoteStatus == "needs_choice" -> "اختار المنتج المطابق" to Color(0xFFF57C00)
        item.priceSource == "quoted" -> "تم التسعير ✓" to Success
        item.priceSource == "manual" -> "سعر مبدئي – يتأكد عند المراجعة" to Color(0xFF0277BD)
        else -> "جاري المطابقة والتسعير…" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(6.dp)) {
        Text(label, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
    }
}

@Composable
private fun CartItemCard(
    item: CartItemEntity, cfg: SheinPricingConfig, vm: SheinViewModel, admin: Boolean,
    onOpen: (String) -> Unit, onTeam: () -> Unit,
) {
    var size by remember(item.id) { mutableStateOf(item.size) }
    var color by remember(item.id) { mutableStateOf(item.color) }
    var notes by remember(item.id) { mutableStateOf(item.notes) }
    var manual by remember(item.id) { mutableStateOf(if (item.priceSource == "manual") item.priceSar?.toString().orEmpty() else "") }
    var choose by remember { mutableStateOf(false) }
    // Persist from local field state so concurrent edits never overwrite each other with stale values.
    fun persist(quantity: Int = item.quantity) {
        val base = item.copy(size = size, color = color, notes = notes, quantity = quantity)
        val updated = if (item.priceSource == "quoted" || item.priceSource == "auto" || !admin) base else {
            val p = manual.toDoubleOrNull()?.takeIf { it > 0 }
            base.copy(priceSar = p, priceSource = p?.let { "manual" })
        }
        vm.update(updated)
    }

    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                val img = item.imageUrl ?: item.localImagePath?.let { File(it).toURI().toString() }
                NetImage(img, Modifier.size(84.dp).clip(RoundedCornerShape(12.dp)))
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.displayTitle(), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    StatusChip(item)
                    (item.productUrl ?: item.saUrl)?.let { url ->
                        Row(Modifier.clickable { onOpen(url) }, verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                            Text(" عرض المنتج", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                IconButton(onClick = { vm.remove(item) }) { Icon(Icons.Outlined.Delete, "حذف") }
            }
            item.adminNote?.takeIf { it.isNotBlank() }?.let { Text("ملاحظة: $it", style = MaterialTheme.typography.bodySmall) }
            if (item.quoteStatus == "pricing") androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            if (item.quoteStatus == "auto_failed" || item.quoteStatus == "team") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.quoteStatus == "auto_failed") OutlinedButton(onClick = { vm.retry(item) }) { Text("حاول تاني") }
                    Button(onClick = onTeam, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366))) { Text("ابعته لفريقنا") }
                }
            }
            if (item.quoteStatus == "needs_choice") {
                OutlinedButton(onClick = { choose = true }, modifier = Modifier.fillMaxWidth()) { Text("اختار المنتج المطابق") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(size, { size = it; persist() }, Modifier.weight(1f),
                    label = { Text("المقاس") }, singleLine = true)
                OutlinedTextField(color, { color = it; persist() }, Modifier.weight(1f),
                    label = { Text("اللون") }, singleLine = true)
            }
            OutlinedTextField(notes, { notes = it; persist() }, Modifier.fillMaxWidth(),
                label = { Text("ملاحظات") }, maxLines = 3)
            if (admin && item.priceSource != "quoted" && item.priceSource != "auto") {
                OutlinedTextField(manual, { v ->
                    manual = v.filter { it.isDigit() || it == '.' }
                    persist()
                }, Modifier.fillMaxWidth(), label = { Text("سعر SHEIN السعودية (لو تعرفه)") }, suffix = { Text("ر.س") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الكمية", Modifier.weight(1f))
                IconButton(onClick = { if (item.quantity > 1) persist(item.quantity - 1) }) { Icon(Icons.Outlined.Remove, "أقل") }
                Text("${item.quantity}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(28.dp))
                IconButton(onClick = { if (item.quantity < 50) persist(item.quantity + 1) }) { Icon(Icons.Outlined.Add, "أكتر") }
            }
            if (item.isPriced()) {
                val l = SheinPricing.line(BigDecimal.valueOf(item.priceSar!!), item.quantity, cfg)
                if (admin) Text("أدمن: ${Money.sar(l.sarUnit)} × ${l.quantity} → ${Money.egp(l.convertedEgp)} + ${Money.egp(l.extraEgp)} = ${Money.egp(l.lineTotalEgp)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Money.egp(l.lineTotalEgp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    if (choose) {
        val cands = vm.candidates(item)
        CandidateDialog(cands, onDismiss = { choose = false }) { idx -> vm.choose(item, idx); choose = false }
    }
}

@Composable
private fun CandidateDialog(cands: List<CandidateDto>, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("أي منتج تقصد؟") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cands.size) { i ->
                    val c = cands[i]
                    Card(Modifier.fillMaxWidth().clickable { onPick(i) }) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            NetImage(c.imageUrl, Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)))
                            Column(Modifier.padding(horizontal = 8.dp)) {
                                Text(c.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(Money.sar(c.originalPriceSar), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
