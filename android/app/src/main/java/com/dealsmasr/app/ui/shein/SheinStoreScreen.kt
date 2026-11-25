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

private const val SHEIN_SA = "https://ar.shein.com/"   // SHEIN GCC storefront; the country is set to Saudi Arabia on the site
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

/** Any SHEIN country link → the same page on SHEIN GCC (ar.shein.com, Saudi Arabia selected there). */
internal fun toSaudi(url: String): String = runCatching {
    val u = Uri.parse(url)
    val h = u.host ?: return url
    if (h == "ar.shein.com" || !Regex("^(www|sa|eg|bh|ae|kw|qa|om|en)\\.shein\\.com$").matches(h)) url
    else u.buildUpon().authority("ar.shein.com").build().toString()
}.getOrDefault(url)

/** Opens SHEIN's own "search by photo" (the camera icon) so the picked photo is searched on SHEIN itself. */
private val CAMERA_JS = """
(function(){
 function vis(e){var r=e.getBoundingClientRect();return r.width>0&&r.height>0;}
 var f=Array.prototype.find.call(document.querySelectorAll('input[type=file]'),function(x){return /image/.test(x.accept||'image')});
 var up=Array.prototype.find.call(document.querySelectorAll('button,a,div,span,li'),function(e){return vis(e)&&e.children.length<3&&/^(ألبوم|الألبوم|رفع صورة|تحميل صورة|اختر صورة|الصور|Album|Upload|Photos|Choose photo)/i.test((e.textContent||'').trim());});
 if(up){up.click();return 'upload';}
 var cam=Array.prototype.find.call(document.querySelectorAll('[class*="camera"],[class*="Camera"],[aria-label*="camera" i],[class*="image-search"],[class*="imageSearch"],[class*="search-img"],[class*="picSearch"]'),vis);
 if(cam){cam.click();return 'camera';}
 if(f){f.click();return 'input';}
 return 'none';})()
""".trimIndent()

/**
 * Reads the page the user is looking at: hides SHEIN's own (riyal) prices so only our Egyptian price shows, and on a
 * product page returns the product id, name, picture, original (crossed-out) price and the size / colour the user
 * tapped on the page. Runs again every second because SHEIN changes pages without reloading.
 */
private val READ_JS = """
(function(){
  // hide only the small riyal price labels themselves (a CSS rule on "*price*" classes also hid whole result lists → white page)
  document.querySelectorAll('[class*="price"],[class*="Price"],[class*="discount"]').forEach(function(e){
    if(e.getAttribute('data-dmh'))return;var t=e.textContent||'';
    if(t.length<40&&/\d/.test(t)&&!e.querySelector('img,a,ul,section')){e.style.visibility='hidden';e.setAttribute('data-dmh','1');}});
  var u=location.href, m=u.match(/-p-(\d{5,})/)||u.match(/goods_id=(\d{5,})/)||u.match(/\/goods\/(\d{5,})/);
  if(!m){var pe0=document.querySelector('[class*="price"]');return JSON.stringify({page:'list',cur:pe0?pe0.textContent.slice(0,30):''});}
  var gid=m[1];
  function num(t){if(!t)return null;var x=String(t).replace(/[٠-٩]/g,function(d){return '٠١٢٣٤٥٦٧٨٩'.indexOf(d)}).replace(/٫/g,'.').replace(/,/g,'').match(/\d+(\.\d+)?/);return x?parseFloat(x[0]):null;}
  var retail=null, cur='';
  // the product's own price block = the first price on the page; its HIGHEST amount is the regular price
  // (not the sale or "with coupon" price). It follows the size the user picked.
  var pe=Array.prototype.find.call(document.querySelectorAll('[class*="price"],[class*="Price"]'),function(e){return /\d/.test(e.textContent)&&e.textContent.length<120;});
  if(pe){var box=pe;for(var k=0;k<4&&box.parentElement&&box.parentElement.textContent.length<220;k++)box=box.parentElement;
    cur=box.textContent;var txt=box.textContent.replace(/[٠-٩]/g,function(d){return '٠١٢٣٤٥٦٧٨٩'.indexOf(d)}).replace(/٫/g,'.').replace(/,/g,'').replace(/\d+(\.\d+)?\s*%/g,' ').replace(/\d+(\.\d+)?\s*[kK+]/g,' ');
    var nums=(txt.match(/\d+(?:\.\d+)?/g)||[]).map(parseFloat).filter(function(v){return v>=1&&v<100000;});
    if(nums.length)retail=Math.max.apply(null,nums);}
  if(!retail){var html=document.documentElement.innerHTML, i=html.indexOf('"goods_id":"'+gid+'"'); if(i<0)i=html.indexOf('"goods_id":'+gid);
    if(i>=0){var seg=html.slice(i,i+20000), r=seg.match(/"retailPrice"\s*:\s*\{[^{}]*?"amount"\s*:\s*"?([\d.]+)"?[^{}]*?\}/);
      if(r){retail=parseFloat(r[1]); var sy=r[0].match(/"amountWithSymbol"\s*:\s*"([^"]*)"/); if(sy)cur=sy[1];}}}
  var sizeBox=Array.prototype.find.call(document.querySelectorAll('[class*="size"]'),function(e){return /(^|\s)(XXS|XS|S|M|L|XL|XXL|\d{2})(\s|$)/.test(e.textContent)&&e.children.length>2;});
  var sar=/(ر\.?\s?س|SR|SAR|﷼|\u20C1)/i.test(cur), egp=/(EGP|ج\.?\s?م|جنيه|E£)/i.test(cur);
  function picked(re){var el=Array.prototype.find.call(document.querySelectorAll('[class*="active"],[class*="selected"],[aria-checked="true"],[aria-selected="true"]'),
    function(e){return re.test(e.className||'')||re.test((e.parentElement&&e.parentElement.className)||'')});
    if(!el)return '';var im=el.querySelector('img');return ((im&&(im.alt||im.title))||el.getAttribute('aria-label')||el.title||el.textContent||'').trim().slice(0,40);}
  var t=(document.querySelector('h1')||{}).textContent||(document.querySelector('meta[property="og:title"]')||{}).content||'';
  var img=(document.querySelector('meta[property="og:image"]')||{}).content||'';
  return JSON.stringify({page:'product',cur:String(cur).slice(0,30),gid:gid,url:u.split('#')[0],title:t.trim().slice(0,200),image:img,retail:retail,sar:sar,egp:egp,
    size:picked(/size/i),color:picked(/colou?r/i),needSize:!!sizeBox});
})()
""".trimIndent()

private data class PageProduct(val gid: String, val url: String, val title: String, val image: String?, val retailSar: Double?,
    val currencyOk: Boolean, val size: String, val color: String, val needSize: Boolean)

private fun parse(o: JsonObject): PageProduct? {
    if (o["page"]?.jsonPrimitive?.contentOrNull != "product") return null
    fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    val egp = o["egp"]?.jsonPrimitive?.contentOrNull == "true"
    return PageProduct(s("gid"), s("url"), s("title"), s("image").ifBlank { null }?.let { if (it.startsWith("//")) "https:$it" else it },
        o["retail"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 }, !egp, s("size"), s("color"), s("needSize") == "true")
}

/** SHEIN's own camera (photo search) button → our photo search (SHEIN's needs its app / camera access). */
private class PhotoBridge {
    var onPick: () -> Unit = {}
    @android.webkit.JavascriptInterface
    fun pickPhoto() { android.os.Handler(android.os.Looper.getMainLooper()).post { onPick() } }
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
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
    var product by remember { mutableStateOf<PageProduct?>(null) }
    var sheet by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }
    var otherCurrency by remember { mutableStateOf<String?>(null) }   // SHEIN opened on another GCC country
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // SHEIN asking for a file (its own photo search) with no photo picked from our button → let the user choose one
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) }); fileCallback = null
    }
    val bridge = remember { PhotoBridge() }
    val web = remember {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("ar.shein.com", "currency=SAR; path=/")
        }
        WebView(ctx).apply {
            this.settings.javaScriptEnabled = true
            this.settings.domStorageEnabled = true
            this.settings.textZoom = 100
            addJavascriptInterface(bridge, "DM")   // only "pickPhoto": SHEIN's camera button opens our photo search
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
                    pendingImage?.let { img -> pendingImage = null; callback.onReceiveValue(arrayOf(img)); return true }   // our photo button
                    fileCallback?.onReceiveValue(null); fileCallback = callback
                    chooser.launch("image/*"); return true
                }
            }
            loadUrl(SHEIN_SA)
        }
    }
    // photo search: our free AI vision reads the photo → SHEIN's own search shows the matching products
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        product = null; message = "بندوّر بالصورة في شي إن…"; pendingImage = uri
        scope.launch {
            // 1) SHEIN's own photo search: open it and hand it the photo (identical results to SHEIN)
            var step = 0
            while (pendingImage != null && step < 14) {
                if (step == 4) {   // no camera on this page → open a SHEIN results page (its search bar has the camera)
                    web.evaluateJavascript("(function(){location.href=location.origin+((location.pathname.match(/^\\/[a-z]{2}(?=\\/)/)||[''])[0])+'/pdsearch/dress/';})()", null)
                    delay(3500)
                }
                web.evaluateJavascript(CAMERA_JS, null)
                delay(1000); step++
            }
            if (pendingImage == null) { message = null; return@launch }   // SHEIN took the photo
            // 2) fallback: describe the photo and search SHEIN with those words
            pendingImage = null
            c.shein.imageSearchQuery(uri).onSuccess { (q, ar) ->
                message = "نتايج شبه الصورة" + (if (ar.isNotBlank()) ": $ar" else "")
                web.loadUrl(searchUrl(q))
            }.onFailure { message = it.message ?: "معرفناش ندور بالصورة دي" }
        }
    }
    bridge.onPick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    LaunchedEffect(web) {
        while (true) {
            web.evaluateJavascript(READ_JS) { r ->
                val o = runCatching {
                    c.api.json.parseToJsonElement(c.api.json.parseToJsonElement(r).jsonPrimitive.content).jsonObject
                }.getOrNull()
                product = o?.let { parse(it) }
                val cur = o?.get("cur")?.jsonPrimitive?.contentOrNull.orEmpty()
                otherCurrency = when {
                    cur.isBlank() || Regex("ر\\.?\\s?س|SR|SAR|﷼|\u20C1", RegexOption.IGNORE_CASE).containsMatchIn(cur) -> null
                    Regex("BHD|د\\.?\\s?ب").containsMatchIn(cur) -> "البحرين"
                    Regex("AED|د\\.?\\s?إ").containsMatchIn(cur) -> "الإمارات"
                    Regex("KWD|د\\.?\\s?ك").containsMatchIn(cur) -> "الكويت"
                    Regex("QAR|ر\\.?\\s?ق").containsMatchIn(cur) -> "قطر"
                    Regex("OMR|ر\\.?\\s?ع").containsMatchIn(cur) -> "عُمان"
                    Regex("JOD|د\\.?\\s?أ").containsMatchIn(cur) -> "الأردن"
                    Regex("EGP|ج\\.?\\s?م|E£").containsMatchIn(cur) -> "مصر"
                    else -> null
                }
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
            // fixed slim bar (no moving parts): brand · photo search · link · cart
            Row(Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF070D1F), Color(0xFF1B2D5C))))
                .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SHEIN × عروض مصر", color = Gold, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
                    Text("لحد باب بيتك · 0 جمارك · 0 مخاطرة · خصومات لحد 80%", color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                IconButton(onClick = { photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Icon(Icons.Outlined.Image, "ابحث بالصورة", tint = Color.White)
                }
                IconButton(onClick = { showLink = !showLink }) { Icon(Icons.Outlined.Link, "لينك منتج", tint = Color.White) }
                IconButton(onClick = onCart) {
                    BadgedBox(badge = { if (cart.isNotEmpty()) Badge { Text("${cart.size}") } }) {
                        Icon(Icons.Outlined.ShoppingCart, "السلة", tint = Color.White)
                    }
                }
            }
            if (showLink) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(link, { link = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("الصق لينك منتج من شي إن") }, shape = RoundedCornerShape(20.dp))
                Spacer(Modifier.width(6.dp))
                Button(onClick = {
                    val u = SheinLink.firstUrl(link)
                    if (u != null && SheinLink.isSheinUrl(u)) { open(u); link = ""; showLink = false } else message = "ده مش لينك شي إن"
                }) { Text("افتح") }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            otherCurrency?.let { country ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text("شي إن فاتحة على $country. افتح قايمة شي إن (☰) ← الدولة/المنطقة ← اختار «المملكة العربية السعودية» مرة واحدة بس، وبعدها كل الأسعار هتتحسب صح.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().padding(10.dp))
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            AndroidView({ web }, Modifier.weight(1f).fillMaxWidth())
            product?.let { p ->
                val waitSize = p.needSize && p.size.isBlank()
                val each = p.retailSar?.takeIf { p.currencyOk && !waitSize }?.let { SheinPricing.line(BigDecimal(it.toString()), 1, cfg).lineTotalEgp }
                Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (each != null) {
                                    Text("السعر لحد باب بيتك", style = MaterialTheme.typography.labelMedium)
                                    Text(Money.egp(each), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary)
                                } else Text(if (waitSize) "اختار المقاس الأول وهنحسبلك السعر لحد باب بيتك" else "اختار المنتج وإحنا هنسعّره لك",
                                    style = MaterialTheme.typography.bodyMedium)
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
