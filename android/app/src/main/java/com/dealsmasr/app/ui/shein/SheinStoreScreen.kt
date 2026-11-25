package com.dealsmasr.app.ui.shein

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Image
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

private const val SHEIN_SA = "https://sa.shein.com/"   // the Saudi storefront (prices in riyals)
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

/** Any SHEIN country link → the same page on the Saudi site. */
internal fun toSaudi(url: String): String = runCatching {
    val u = Uri.parse(url)
    val h = u.host ?: return url
    if (h == "sa.shein.com" || !Regex("^(www|ar|eg|bh|ae|kw|qa|om|en)\\.shein\\.com$").matches(h)) url
    else u.buildUpon().authority("sa.shein.com").build().toString()
}.getOrDefault(url)

/** Opens SHEIN's own "search by photo" (the camera icon) so the picked photo is searched on SHEIN itself. */
private val CAMERA_JS = """
(function(){var s=['[class*="camera"]','[aria-label*="camera" i]','[class*="image-search"]','[class*="imageSearch"]','[class*="pic-search"]','[class*="picSearch"]','[class*="search-img"]'];
 for(var i=0;i<s.length;i++){var e=document.querySelector(s[i]);if(e){e.click();return 'clicked';}}
 var f=document.querySelector('input[type=file]'); if(f){f.click();return 'input';} return 'none';})()
""".trimIndent()

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
    var showLink by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var header by remember { mutableStateOf(true) }
    var product by remember { mutableStateOf<PageProduct?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // SHEIN asking for a file (its own photo search) with no photo picked from our button → let the user choose one
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) }); fileCallback = null
    }
    val web = remember {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("sa.shein.com", "currency=SAR; path=/")
        }
        WebView(ctx).apply {
            this.settings.javaScriptEnabled = true
            this.settings.domStorageEnabled = true
            this.settings.textZoom = 100
            val rewrites = HashMap<String, Int>()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    val host = u.host ?: ""
                    if (u.scheme != "https" || !(host.endsWith("shein.com") || host.endsWith("shein.top"))) return true   // no app-store / deep links
                    val sa = toSaudi(u.toString())
                    if (request.isForMainFrame && sa != u.toString() && (rewrites.merge(sa, 1, Int::plus) ?: 0) <= 2) {
                        view.loadUrl(sa); return true   // keep the user on the Saudi store (riyal prices)
                    }
                    return false
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                override fun onPageFinished(view: WebView, url: String?) { loading = false }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    val img = pendingImage
                    if (img != null) { pendingImage = null; callback.onReceiveValue(arrayOf(img)); return true }
                    fileCallback?.onReceiveValue(null); fileCallback = callback
                    chooser.launch("image/*"); return true
                }
            }
            setOnScrollChangeListener { _, _, y, _, oldY ->
                if (y > oldY + 12 && y > 120) header = false else if (y < oldY - 12 || y < 40) header = true
            }
            loadUrl(SHEIN_SA)
        }
    }
    // photo search: pick a photo, open SHEIN's own camera search and hand it the photo
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        pendingImage = uri; product = null; header = true
        web.loadUrl(SHEIN_SA)
        scope.launch {
            repeat(12) {
                delay(1000)
                if (pendingImage == null) return@launch          // SHEIN took the photo
                web.evaluateJavascript(CAMERA_JS, null)
            }
            pendingImage?.let { u ->   // SHEIN's photo search didn't open → our team finds it from the photo
                pendingImage = null
                c.shein.addByImage(u, null)
                message = "بعتنا الصورة لفريقنا يدوّر عليها في شي إن ويسعّرها — هتلاقيها في السلة"
            }
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
    fun open(url: String) { product = null; message = null; web.loadUrl(toSaudi(url)) }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { openUrl(ctx, "https://wa.me/${settings?.whatsappNumber ?: "201050038728"}") },
            containerColor = Color(0xFF25D366), contentColor = Color.White, shape = CircleShape,
            modifier = Modifier.padding(bottom = if (product != null) 130.dp else 0.dp)) {
            Icon(Icons.AutoMirrored.Outlined.Chat, "واتساب")
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            AnimatedVisibility(visible = header) {
                Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xFF070D1F), Color(0xFF1B2D5C))))) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("SHEIN × عروض مصر", color = Gold, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                            Text("منتجات شي إن لحد باب بيتك · 0 جمارك · 0 مخاطرة · خصومات لحد 80%", color = Color.White,
                                style = MaterialTheme.typography.labelSmall, maxLines = 1)
                        }
                        IconButton(onClick = onCart) {
                            BadgedBox(badge = { if (cart.isNotEmpty()) Badge { Text("${cart.size}") } }) {
                                Icon(Icons.Outlined.ShoppingCart, "السلة", tint = Color.White)
                            }
                        }
                    }
                    TextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp).clip(RoundedCornerShape(24.dp)),
                        placeholder = { Text("دوّر في شي إن… فستان، جزمة، شنطة") }, singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                        trailingIcon = {
                            Row {
                                IconButton(onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                                    Icon(Icons.Outlined.Image, "ابحث بالصورة")
                                }
                                IconButton(onClick = { showLink = !showLink }) { Icon(Icons.Outlined.Link, "لينك منتج") }
                            }
                        },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { if (query.isNotBlank()) open(searchUrl(query)) }),
                        colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                    if (showLink) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(link, { link = it }, Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("الصق لينك منتج من شي إن", color = Color.White.copy(alpha = 0.7f)) },
                            shape = RoundedCornerShape(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Button(onClick = {
                            val u = SheinLink.firstUrl(link)
                            if (u != null && SheinLink.isSheinUrl(u)) { open(u); link = ""; showLink = false } else message = "ده مش لينك شي إن"
                        }) { Text("افتح") }
                    }
                    if (product == null) LazyRow(contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(SHEIN_CATS.size) { i ->
                            val (label, icon, q) = SHEIN_CATS[i]
                            Column(Modifier.width(66.dp).clip(RoundedCornerShape(14.dp)).clickable { open(searchUrl(q)) }.padding(2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(Modifier.size(42.dp).background(Gold.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(icon, null, tint = Gold)
                                }
                                Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White, maxLines = 1)
                            }
                        }
                    }
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            AndroidView({ web }, Modifier.weight(1f).fillMaxWidth())
            product?.let { p ->
                val each = p.retailSar?.takeIf { p.currencyOk }?.let { SheinPricing.line(BigDecimal(it.toString()), 1, cfg).lineTotalEgp }
                Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (each != null) {
                                    Text("السعر لحد باب بيتك", style = MaterialTheme.typography.labelMedium)
                                    Text(Money.egp(each), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary)
                                } else Text("اختار المنتج وإحنا هنسعّره لك", style = MaterialTheme.typography.bodyMedium)
                            }
                            Button(onClick = { sheet = true }, modifier = Modifier.height(52.dp)) { Text("اطلب ✓", style = MaterialTheme.typography.titleMedium) }
                        }
                        if (cfg.deliveryFeeEgp.signum() > 0) Text("+ ${Money.egp(cfg.deliveryFeeEgp)} مصاريف شحن مرة واحدة للطلب كله",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                unit?.let {
                    Text("لحد باب بيتك: ${Money.egp(SheinPricing.line(BigDecimal(it.toString()), qty, cfg).lineTotalEgp)}",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                    if (cfg.deliveryFeeEgp.signum() > 0) Text("+ ${Money.egp(cfg.deliveryFeeEgp)} مصاريف شحن مرة واحدة للطلب",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(if (busy) "لحظة…" else "كمّل الطلب (الاسم والعنوان والموبايل)")
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}
