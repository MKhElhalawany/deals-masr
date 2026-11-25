package com.dealsmasr.app.assist

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Jumia listing pages build their product grid in the browser, so a plain download can contain no products.
 * This opens the page once in an invisible WebView on the user's phone (owner-approved, Jumia only), reads the
 * product cards after the page finishes loading, then destroys the view. It never interacts with the page.
 * If the store shows a protection / verification page, it stops at once and reports "blocked" — nothing is solved.
 */
object JumiaWebView {
    data class Result(val status: String, val items: List<JsonObject>, val links: List<String>, val note: String)

    private val JS = """
(function(){
  var t=(document.title||'')+' '+(document.body?document.body.innerText.slice(0,400):'');
  if(/Just a moment|Attention Required|cf-chl|captcha|Verify you are human/i.test(t+document.documentElement.innerHTML.slice(0,3000)))
    return JSON.stringify({challenge:true});
  function num(s){if(!s)return null;var m=String(s).replace(/,/g,'').match(/\d+(\.\d+)?/);return m?parseFloat(m[0]):null;}
  var out=[];
  document.querySelectorAll('article.prd, article[class*="prd"]').forEach(function(a){
    var l=a.querySelector('a.core, a[href$=".html"]'); if(!l)return;
    var href=l.getAttribute('href')||''; if(href.indexOf('/')!==0){try{href=new URL(href).pathname}catch(e){return}}
    var sku=l.getAttribute('data-gtm-id')||l.getAttribute('data-ga4-item_id')||l.getAttribute('data-id')||((href.match(/-(\d{5,})\.html/)||[])[1]);
    var name=(a.querySelector('.name')||{}).textContent||l.getAttribute('data-gtm-name')||l.getAttribute('data-ga4-item_name');
    var prc=num((a.querySelector('.prc')||{}).textContent), old=num((a.querySelector('.old')||{}).textContent);
    var img=a.querySelector('img'); img=img?(img.getAttribute('data-src')||img.getAttribute('src')):null;
    var rate=num(((a.querySelector('.stars')||{}).textContent||'').split(' ')[0]);
    var cnt=num((a.querySelector('.rev')||{}).textContent);
    var brand=l.getAttribute('data-gtm-brand')||l.getAttribute('data-ga4-item_brand');
    if(sku&&name&&prc&&old&&old>prc) out.push({sku:String(sku),title:name.trim(),url:'https://www.jumia.com.eg'+href,price:prc,old:old,
      image_url:img,rating:rate,rating_count:cnt,brand:brand});
  });
  if(!out.length&&window.__STORE__){try{(window.__STORE__.products||[]).forEach(function(p){
    var pr=p.prices||{}; var c=num(pr.rawPrice), o=num(pr.rawOldPrice);
    if(p.sku&&p.url&&c&&o&&o>c) out.push({sku:p.sku,title:p.displayName||p.name,url:'https://www.jumia.com.eg'+p.url,price:c,old:o,
      image_url:p.image,brand:p.brand,rating:(p.rating||{}).average,rating_count:(p.rating||{}).totalRatings});
  })}catch(e){}}
  var links=[];document.querySelectorAll('a[href]').forEach(function(x){var h=x.getAttribute('href');if(/^\/[a-z0-9-]+\/$/.test(h)&&links.indexOf(h)<0&&links.length<60)links.push(h)});
  return JSON.stringify({items:out.slice(0,80),links:links,cards:document.querySelectorAll('article.prd').length,title:document.title.slice(0,60)});
})()
""".trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun read(ctx: Context, url: String, json: Json): Result = withContext(Dispatchers.Main) {
        var view: WebView? = null
        val raw = withTimeoutOrNull(35_000) {
            suspendCancellableCoroutine<String?> { cont ->
                val w = WebView(ctx.applicationContext)
                view = w
                w.settings.javaScriptEnabled = true
                w.settings.domStorageEnabled = true
                w.settings.blockNetworkImage = true          // product data only, saves data
                w.settings.loadsImagesAutomatically = false
                var done = false
                w.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(v: WebView, u: String?) {
                        if (done) return
                        // give the page's own script a moment to draw the product grid, then read it once
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (done) return@postDelayed
                            v.evaluateJavascript(JS) { r ->
                                if (done) return@evaluateJavascript
                                done = true
                                if (cont.isActive) cont.resume(r)
                            }
                        }, 2500)
                    }
                }
                w.loadUrl(url)
            }
        }
        view?.apply { stopLoading(); loadUrl("about:blank"); destroy() }
        // cookies are kept (they also hold the user's SHEIN country choice in the in-app SHEIN browser)
        if (raw == null || raw == "null") return@withContext Result("error", emptyList(), emptyList(), "webview timeout")
        val o = runCatching { json.parseToJsonElement(json.parseToJsonElement(raw).jsonPrimitive.content).jsonObject }.getOrNull()
            ?: return@withContext Result("error", emptyList(), emptyList(), "webview parse")
        if (o["challenge"] != null) return@withContext Result("blocked", emptyList(), emptyList(), "webview challenge")
        val items = o["items"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val links = o["links"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        Result("ok", items, links, "webview cards=${o["cards"]} items=${items.size}")
    }
}
