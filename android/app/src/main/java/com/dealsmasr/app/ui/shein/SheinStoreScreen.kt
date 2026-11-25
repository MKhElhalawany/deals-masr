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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.TextButton
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
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Groups
import android.view.ViewGroup
import android.content.MutableContextWrapper
import java.net.URLEncoder

private val AR_HEADERS = mapOf("Accept-Language" to "ar-SA,ar;q=0.9")   // SHEIN in Arabic
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

private const val GROUP_LINK = "https://chat.whatsapp.com/BgvVPa3GyLbI8yuVyonmlM"

/**
 * One SHEIN browser for the whole app session: switching tabs and coming back shows the same page (no reload, no
 * black screen). If Android kills the page's renderer, a fresh browser is created and the last page reopened.
 */
@SuppressLint("StaticFieldLeak")
private object SheinWeb {
    private var view: WebView? = null
    private var ctxWrap: MutableContextWrapper? = null
    var lastUrl: String = SHEIN_SA
    val logs = ArrayList<Pair<String, String>>()   // kind to message (sent to our server for fixing problems)

    @SuppressLint("SetJavaScriptEnabled")
    fun get(activity: android.content.Context): WebView {
        view?.let { ctxWrap?.baseContext = activity; return it }
        val wrap = MutableContextWrapper(activity).also { ctxWrap = it }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setCookie("ar.shein.com", "currency=SAR; path=/")
        return WebView(wrap).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.textZoom = 100
            settings.javaScriptCanOpenWindowsAutomatically = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.userAgentString = settings.userAgentString.replace("; wv)", ")").replace(Regex("Version/[\\d.]+ "), "")
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            setBackgroundColor(android.graphics.Color.WHITE)
            loadUrl(lastUrl, AR_HEADERS)
            view = this
        }
    }

    fun reset() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it); runCatching { it.destroy() } }
        view = null
    }

    fun log(kind: String, msg: String) = synchronized(logs) { if (logs.size < 40) logs += kind to msg.take(300) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheinStoreScreen(onCart: () -> Unit, onCheckout: () -> Unit) {
    val c = appContainer()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.settings.settings.collectAsStateWithLifecycle(null)
    val cart by c.shein.cart.collectAsStateWithLifecycle(emptyList())
    val cfg = settings?.pricing ?: SheinPricingConfig()
    var loading by remember { mutableStateOf(false) }
    var product by remember { mutableStateOf<PageProduct?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var otherCurrency by remember { mutableStateOf<String?>(null) }
    var howTo by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf(0) }
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // SHEIN asking for a photo (its own photo search) → the phone's photo picker
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        fileCallback?.onReceiveValue(uri?.let { arrayOf(it) }); fileCallback = null
    }
    val web = remember(generation) {
        SheinWeb.get(ctx).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    val host = u.host ?: ""
                    if (u.scheme != "https" || !(host.endsWith("shein.com") || host.endsWith("shein.top"))) return true   // no app-store / deep links
                    val sa = toSaudi(u.toString())
                    if (request.isForMainFrame && sa != u.toString()) { view.loadUrl(sa, AR_HEADERS); return true }
                    return false
                }
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                override fun onPageFinished(view: WebView, url: String?) { loading = false; url?.let { SheinWeb.lastUrl = it } }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError) {
                    if (request.isForMainFrame) SheinWeb.log("load_error", "${error.errorCode} ${error.description} ${request.url}")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, r: android.webkit.WebResourceResponse) {
                    if (request.isForMainFrame || r.statusCode >= 500) SheinWeb.log("http_${r.statusCode}", request.url.toString())
                }
                override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                    SheinWeb.log("renderer_gone", "crash=${detail.didCrash()} ${SheinWeb.lastUrl}")
                    SheinWeb.reset(); generation++   // fresh browser, same page
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCallback?.onReceiveValue(null); fileCallback = callback
                    chooser.launch("image/*"); return true
                }
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR) SheinWeb.log("js_error", "${m.message()} @${m.sourceId().substringAfterLast('/')}:${m.lineNumber()}")
                    return true
                }
            }
        }
    }
    // read the open product (price after the size is chosen) + send diagnostics now and then
    LaunchedEffect(web) {
        var tick = 0
        while (true) {
            web.evaluateJavascript(READ_JS) { r ->
                val o = runCatching { c.api.json.parseToJsonElement(c.api.json.parseToJsonElement(r).jsonPrimitive.content).jsonObject }.getOrNull()
                product = o?.let { parse(it) }
                val cur = o?.get("cur")?.jsonPrimitive?.contentOrNull.orEmpty()
                otherCurrency = when {
                    cur.isBlank() || Regex("ر\\.?\\s?س|SR|SAR|﷼|⃁", RegexOption.IGNORE_CASE).containsMatchIn(cur) -> null
                    Regex("BHD|د\\.?\\s?ب").containsMatchIn(cur) -> "البحرين"
                    Regex("AED|د\\.?\\s?إ").containsMatchIn(cur) -> "الإمارات"
                    Regex("KWD|د\\.?\\s?ك").containsMatchIn(cur) -> "الكويت"
                    Regex("QAR|ر\\.?\\s?ق").containsMatchIn(cur) -> "قطر"
                    Regex("OMR|ر\\.?\\s?ع").containsMatchIn(cur) -> "عُمان"
                    Regex("EGP|ج\\.?\\s?م|E£").containsMatchIn(cur) -> "مصر"
                    else -> null
                }
            }
            if (++tick % 30 == 0) {
                val batch = synchronized(SheinWeb.logs) { SheinWeb.logs.toList().also { SheinWeb.logs.clear() } }
                if (batch.isNotEmpty()) launch {
                    runCatching {
                        c.supabase.fn("api", buildJsonObject {
                            put("action", "client_log"); put("app", com.dealsmasr.app.BuildConfig.VERSION_NAME)
                            put("logs", buildJsonArray { batch.forEach { (k, m) -> add(buildJsonObject { put("kind", k); put("message", m); put("url", SheinWeb.lastUrl) }) } })
                        })
                    }
                }
            }
            delay(1500)
        }
    }
    BackHandler(enabled = web.canGoBack()) { web.goBack() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            otherCurrency?.let { country ->
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Text("شي إن فاتحة على $country. من قايمة شي إن (☰) ← الدولة ← اختار «المملكة العربية السعودية» مرة واحدة بس.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth().padding(10.dp))
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
            AndroidView({ (web.parent as? ViewGroup)?.removeView(web); web }, Modifier.weight(1f).fillMaxWidth())
            product?.let { p ->
                val waitSize = p.needSize && p.size.isBlank()
                val each = p.retailSar?.takeIf { p.currencyOk && !waitSize }?.let { SheinPricing.line(BigDecimal(it.toString()), 1, cfg).lineTotalEgp }
                Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            when {
                                waitSize -> Text("اختار المقاس من فوق وهيظهرلك السعر لحد باب بيتك", style = MaterialTheme.typography.bodyMedium)
                                each != null -> {
                                    Text("السعر لحد باب بيتك", style = MaterialTheme.typography.labelMedium)
                                    Text(Money.egp(each), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text("+ ${Money.egp(cfg.deliveryFeeEgp)} شحن ثابت · الدفع عند الاستلام", style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                else -> Text("لحظة… بنحسب السعر", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Button(onClick = {
                            busy = true
                            scope.launch {
                                val unit = p.retailSar?.takeIf { p.currencyOk }
                                val r = if (unit != null) c.shein.addFromBrowser(p.url, p.title, p.image, unit, p.size, p.color, 1)
                                else c.shein.addByUrl(p.url, null)
                                busy = false
                                if (r is AddResult.Error) message = r.message else onCheckout()
                            }
                        }, enabled = !busy && !waitSize, modifier = Modifier.height(52.dp)) {
                            Text(if (busy) "لحظة…" else "اطلب ✓", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
        // round action buttons (WhatsApp · how to order · our group · cart)
        Column(Modifier.align(Alignment.BottomStart).padding(start = 10.dp, bottom = if (product != null) 96.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundAction(Icons.AutoMirrored.Outlined.Chat, "كلّمنا", Color(0xFF25D366)) {
                openUrl(ctx, "https://wa.me/${settings?.whatsappNumber ?: "201050038728"}")
            }
            RoundAction(Icons.Outlined.Groups, "جروبنا", Color(0xFF128C7E)) { openUrl(ctx, GROUP_LINK) }
            RoundAction(Icons.AutoMirrored.Outlined.HelpOutline, "إزاي أطلب", Gold) { howTo = true }
            BadgedBox(badge = { if (cart.isNotEmpty()) Badge { Text("${cart.size}") } }) {
                RoundAction(Icons.Outlined.ShoppingCart, "السلة", Color(0xFF0E1B3D), onCart)
            }
        }
    }

    if (howTo) {
        var link by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(onDismissRequest = { howTo = false },
            title = { Text("إزاي تطلب من شي إن لحد البيت؟") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("1) دوّر على المنتج في شي إن بالاسم أو بالصورة (أيقونة الكاميرا في بحث شي إن).")
                    Text("2) افتح المنتج وحدد المقاس واللون أو عدد القطع.")
                    Text("3) هيظهرلك تحت السعر لحد باب بيتك بالجنيه.")
                    Text("4) هتزود عليه ${Money.egp(cfg.deliveryFeeEgp)} شحن ثابت لكل المحافظات، والدفع عند الاستلام.")
                    Text("5) دوس «اطلب ✓» واكتب اسمك وعنوانك ورقمك — وإحنا نكمّل الباقي ونكلمك.")
                    OutlinedTextField(link, { link = it }, Modifier.fillMaxWidth(), singleLine = true,
                        placeholder = { Text("أو الصق لينك منتج من شي إن") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val u = SheinLink.firstUrl(link)
                    if (u != null && SheinLink.isSheinUrl(u)) { product = null; web.loadUrl(toSaudi(u), AR_HEADERS) }
                    howTo = false
                }) { Text(if (link.isBlank()) "تمام" else "افتح اللينك") }
            })
    }
}

@Composable
private fun RoundAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = color, shadowElevation = 6.dp, modifier = Modifier.size(50.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, label, tint = Color.White) }
        }
        Surface(shape = RoundedCornerShape(8.dp), color = Color.Black.copy(alpha = 0.55f)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
        }
    }
}
