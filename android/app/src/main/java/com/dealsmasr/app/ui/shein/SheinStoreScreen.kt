package com.dealsmasr.app.ui.shein

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Chair
import androidx.compose.material.icons.outlined.Checkroom
import androidx.compose.material.icons.outlined.ChildCare
import androidx.compose.material.icons.outlined.Diamond
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Hiking
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.LocalOffer
import androidx.compose.material.icons.outlined.Man
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.material.icons.outlined.Woman
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.core.SheinLink
import com.dealsmasr.app.core.SheinPricing
import com.dealsmasr.app.core.SheinPricingConfig
import com.dealsmasr.app.data.AddResult
import com.dealsmasr.app.ui.appContainer
import com.dealsmasr.app.ui.i18n.Text
import com.dealsmasr.app.ui.product.openUrl
import com.dealsmasr.app.ui.theme.Gold
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.net.URLEncoder

private const val SHEIN_SA = "https://ar.shein.com/"
private fun searchUrl(q: String) = "${SHEIN_SA}pdsearch/${URLEncoder.encode(q.trim(), "UTF-8").replace("+", "%20")}/"

/** SHEIN Saudi main departments (opened as SHEIN's own pages, with SHEIN's filters). */
private val SHEIN_CATS: List<Triple<String, ImageVector, String>> = listOf(
    Triple("نساء", Icons.Outlined.Woman, "women clothing"),
    Triple("رجال", Icons.Outlined.Man, "men clothing"),
    Triple("أطفال", Icons.Outlined.ChildCare, "kids clothing"),
    Triple("أحذية", Icons.Outlined.Hiking, "shoes"),
    Triple("شنط", Icons.Outlined.ShoppingBag, "bags"),
    Triple("مجوهرات وإكسسوارات", Icons.Outlined.Diamond, "jewelry accessories"),
    Triple("تجميل", Icons.Outlined.Face, "beauty makeup"),
    Triple("المنزل", Icons.Outlined.Chair, "home decor"),
    Triple("إلكترونيات", Icons.Outlined.Headphones, "electronics"),
    Triple("نوم وملابس داخلية", Icons.Outlined.Bedtime, "sleepwear lingerie"),
    Triple("رياضة", Icons.Outlined.FitnessCenter, "sportswear"),
    Triple("مقاسات كبيرة", Icons.Outlined.Checkroom, "plus size"),
    Triple("حيوانات أليفة", Icons.Outlined.Pets, "pet supplies"),
    Triple("تخفيضات", Icons.Outlined.LocalOffer, "sale"),
)

/**
 * Reads the page the user is looking at: hides SHEIN's own (riyal) prices so only our Egyptian price shows, and on a
 * product page returns the product id, name, picture, original (crossed-out) price and the size / colour the user
 * tapped on the page. Runs again every second because SHEIN changes pages without reloading.
 */
private val READ_JS = """
(function(){
  if(!document.getElementById('dm-hide')){var st=document.createElement('style');st.id='dm-hide';
    st.textContent='[class*="price"],[class*="Price"],[class*="discount-label"],[class*="download-banner"],[class*="app-download"],[class*="open-app"]{visibility:hidden!important}';
    (document.head||document.documentElement).appendChild(st);}
  var u=location.href, m=u.match(/-p-(\d{5,})/)||u.match(/goods_id=(\d{5,})/)||u.match(/\/goods\/(\d{5,})/);
  if(!m) return JSON.stringify({page:'list'});
  var gid=m[1];
  function num(t){if(!t)return null;var x=String(t).replace(/[٠-٩]/g,function(d){return '٠١٢٣٤٥٦٧٨٩'.indexOf(d)}).replace(/٫/g,'.').replace(/,/g,'').match(/\d+(\.\d+)?/);return x?parseFloat(x[0]):null;}
  var retail=null, sale=null, cur='';
  var root=document.querySelector('[class*="product-intro"],[class*="goods-detail"],[class*="productIntro"]')||document.body;
  var del=root.querySelector('del,s,[class*="del-price"],[class*="retail"],[class*="original"],[class*="crossed"]');
  if(del){retail=num(del.textContent);cur=del.textContent;}
  var pe=root.querySelector('[class*="price"]'); if(pe){sale=num(pe.textContent); if(!cur)cur=pe.textContent;}
  if(!retail){var html=document.documentElement.innerHTML, i=html.indexOf('"goods_id":"'+gid+'"'); if(i<0)i=html.indexOf('"goods_id":'+gid);
    if(i>=0){var seg=html.slice(i,i+20000), r=seg.match(/"retailPrice"\s*:\s*\{[^{}]*?"amount"\s*:\s*"?([\d.]+)"?[^{}]*?\}/);
      if(r){retail=parseFloat(r[1]); var sy=r[0].match(/"amountWithSymbol"\s*:\s*"([^"]*)"/); if(sy)cur=sy[1];}}}
  if(!retail) retail=sale;
  var sar=/(ر\.?\s?س|SR|SAR|﷼)/i.test(cur), egp=/(EGP|ج\.?\s?م|جنيه|E£)/i.test(cur);
  function picked(re){var el=Array.prototype.find.call(document.querySelectorAll('[class*="active"],[class*="selected"],[aria-checked="true"],[aria-selected="true"]'),
    function(e){return re.test(e.className||'')||re.test((e.parentElement&&e.parentElement.className)||'')});
    if(!el)return '';var im=el.querySelector('img');return ((im&&(im.alt||im.title))||el.getAttribute('aria-label')||el.title||el.textContent||'').trim().slice(0,40);}
  var t=(document.querySelector('h1')||{}).textContent||(document.querySelector('meta[property="og:title"]')||{}).content||'';
  var img=(document.querySelector('meta[property="og:image"]')||{}).content||'';
  return JSON.stringify({page:'product',gid:gid,url:u.split('#')[0],title:t.trim().slice(0,200),image:img,retail:retail,sar:sar,egp:egp,
    size:picked(/size/i),color:picked(/colou?r/i)});
})()
""".trimIndent()

private data class PageProduct(val gid: String, val url: String, val title: String, val image: String?, val retailSar: Double?,
    val currencyOk: Boolean, val size: String, val color: String)

private fun parse(o: JsonObject): PageProduct? {
    if (o["page"]?.jsonPrimitive?.contentOrNull != "product") return null
    fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    val egp = o["egp"]?.jsonPrimitive?.contentOrNull == "true"
    return PageProduct(s("gid"), s("url"), s("title"), s("image").ifBlank { null }?.let { if (it.startsWith("//")) "https:$it" else it },
        o["retail"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }, !egp, s("size"), s("color"))
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheinStoreScreen(onCart: () -> Unit, onCheckout: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val cart by c.shein.cart.collectAsStateWithLifecycle(emptyList())
    val cfg = settings?.pricing ?: SheinPricingConfig()
    var query by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var product by remember { mutableStateOf<PageProduct?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    val web = remember {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // ask SHEIN for the Saudi storefront in riyals
            setCookie(".shein.com", "currency=SAR; path=/"); setCookie("ar.shein.com", "currency=SAR; path=/")
        }
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    // stay on SHEIN pages; app-store / deep links are ignored
                    return !(u.scheme == "https" && (u.host ?: "").let { it.endsWith("shein.com") || it.endsWith("shein.top") })
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                override fun onPageFinished(view: WebView, url: String?) { loading = false }
            }
            loadUrl(SHEIN_SA)
        }
    }
    LaunchedEffect(web) {
        while (true) {
            web.evaluateJavascript(READ_JS) { r ->
                product = runCatching {
                    val inner = c.api.json.parseToJsonElement(r).jsonPrimitive.content
                    parse(c.api.json.parseToJsonElement(inner).jsonObject)
                }.getOrNull()
            }
            delay(1000)
        }
    }
    BackHandler(enabled = web.canGoBack()) { web.goBack() }
    fun open(url: String) { product = null; web.loadUrl(url) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { openUrl(ctx, "https://wa.me/${settings?.whatsappNumber ?: "201050038728"}") },
            containerColor = Color(0xFF25D366), contentColor = Color.White, shape = CircleShape,
            modifier = Modifier.padding(bottom = if (product != null) 120.dp else 0.dp)) {
            Icon(Icons.AutoMirrored.Outlined.Chat, "واتساب")
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            // brand header
            Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF070D1F), Color(0xFF1B2D5C))))
                .padding(horizontal = 14.dp, vertical = 10.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SHEIN × عروض مصر", color = Gold, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f))
                        IconButton(onClick = onCart) {
                            BadgedBox(badge = { if (cart.isNotEmpty()) Badge { Text("${cart.size}") } }) {
                                Icon(Icons.Outlined.ShoppingCart, "السلة", tint = Color.White)
                            }
                        }
                    }
                    Text("عروض مصر بتوفرلك منتجات شي إن لحد باب بيتك", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    Text("0 جمارك · 0 مخاطرة · أسرع وقت · خصومات لحد 80%", color = Gold, style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold)
                }
            }
            // search + link
            TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)),
                placeholder = { Text("دوّر في شي إن… فستان، جزمة، شنطة") }, singleLine = true,
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) open(searchUrl(query)) }),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(link, { link = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("أو الصق لينك منتج من شي إن") }, leadingIcon = { Icon(Icons.Outlined.Link, null) },
                    shape = RoundedCornerShape(20.dp))
                Spacer(Modifier.width(6.dp))
                Button(onClick = {
                    val u = SheinLink.firstUrl(link)
                    if (u != null && SheinLink.isSheinUrl(u)) { open(u); link = "" } else message = "ده مش لينك شي إن"
                }) { Text("افتح") }
            }
            if (product == null) LazyRow(contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SHEIN_CATS.size) { i ->
                    val (label, icon, q) = SHEIN_CATS[i]
                    Column(Modifier.width(72.dp).clip(RoundedCornerShape(14.dp)).clickable { open(searchUrl(q)) }.padding(4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp)) }
            AndroidView({ web }, Modifier.weight(1f).fillMaxWidth())
            product?.let { p ->
                val each = p.retailSar?.takeIf { p.currencyOk }?.let { SheinPricing.line(BigDecimal(it.toString()), 1, cfg).lineTotalEgp }
                Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(p.title, maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (each != null) {
                                    Text("هنوصلهولك لمصر بـ", style = MaterialTheme.typography.labelSmall)
                                    Text(Money.egp(each), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary)
                                } else Text("اختار المنتج وإحنا هنسعّره لك", style = MaterialTheme.typography.bodySmall)
                            }
                            Button(onClick = { sheet = true }, modifier = Modifier.height(48.dp)) { Text("اطلب ✓") }
                        }
                    }
                }
            }
        }
    }

    if (sheet) product?.let { p ->
        var size by remember(p.gid) { mutableStateOf(p.size) }
        var color by remember(p.gid) { mutableStateOf(p.color) }
        var qty by remember(p.gid) { mutableStateOf(1) }
        var busy by remember { mutableStateOf(false) }
        val unit = p.retailSar?.takeIf { p.currencyOk }
        ModalBottomSheet(onDismissRequest = { sheet = false }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(p.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text("اختار المقاس واللون من صفحة شي إن، وهيظهروا هنا. تقدر تعدّلهم.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(size, { size = it }, Modifier.fillMaxWidth(), label = { Text("المقاس") }, singleLine = true)
                OutlinedTextField(color, { color = it }, Modifier.fillMaxWidth(), label = { Text("اللون") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("عدد القطع", Modifier.weight(1f))
                    OutlinedButton(onClick = { if (qty > 1) qty-- }) { Text("−") }
                    Text("  $qty  ", fontWeight = FontWeight.Bold)
                    OutlinedButton(onClick = { if (qty < 20) qty++ }) { Text("+") }
                }
                unit?.let { Text("هنوصلهولك لمصر بـ ${Money.egp(SheinPricing.line(BigDecimal(it.toString()), qty, cfg).lineTotalEgp)}",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary) }
                Button(onClick = {
                    busy = true
                    scope.launch {
                        val r = if (unit != null) c.shein.addFromBrowser(p.url, p.title, p.image, unit, size, color, qty)
                        else c.shein.addByUrl(p.url, null)   // price not readable here → our server prices it
                        busy = false; sheet = false
                        when (r) {
                            is AddResult.Error -> message = r.message
                            else -> if (unit != null) onCheckout() else onCart()
                        }
                    }
                }, enabled = !busy && (size.isNotBlank() || p.size.isBlank()), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (busy) "لحظة…" else "كمّل الطلب (الاسم والعنوان والموبايل)")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
