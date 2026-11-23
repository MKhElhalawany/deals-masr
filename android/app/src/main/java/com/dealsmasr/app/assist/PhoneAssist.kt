package com.dealsmasr.app.assist

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.dealsmasr.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Phone assist — approved by the owner (2026-11-23) for stores that block cloud servers (Jumia).
 * Rules: only while the app is open, only on Wi-Fi, at most one page every ~60 s, the page comes ONLY from the
 * server's queue (robots.txt-checked there), one plain request (no browser, no cookies, no challenge solving:
 * a protection page is just reported as "blocked"). Only compact product data is sent back; the server validates it.
 */
object PhoneAssist {
    private const val TAG = "PhoneAssist"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true).build()
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /** One assist step; returns a short status for logs, or null when nothing was done. */
    suspend fun step(ctx: Context, c: AppContainer): String? = withContext(Dispatchers.IO) {
        if (!c.auth.isSignedIn || !c.settings.current().phoneAssist || !onWifi(ctx)) return@withContext null
        val claim = runCatching {
            c.api.json.parseToJsonElement(c.supabase.rpcWith("claim_phone_target", buildJsonObject { put("p_store", "jumia") })).jsonArray
        }.getOrNull()?.firstOrNull()?.jsonObject ?: return@withContext null
        val id = claim["id"]?.jsonPrimitive?.longOrNull ?: return@withContext null
        val url = claim["url"]?.jsonPrimitive?.content ?: return@withContext null
        if (!url.startsWith("https://www.jumia.com.eg/")) return@withContext null
        val t0 = System.currentTimeMillis()
        var status = "error"
        var cards: List<JsonObject> = emptyList()
        var links: List<String> = emptyList()
        runCatching {
            http.newCall(Request.Builder().url(url).header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml").header("Accept-Language", "ar-EG,ar;q=0.9,en;q=0.8").build())
                .execute().use { r ->
                    val html = r.body?.string().orEmpty()
                    val challenge = Regex("Just a moment|cf-chl|challenges\\.cloudflare|captcha", RegexOption.IGNORE_CASE).containsMatchIn(html.take(20000))
                    if (r.code in listOf(403, 429, 503) || challenge) status = "blocked"
                    else if (r.isSuccessful) { cards = JumiaCards.extract(html); links = JumiaCards.links(html); status = "ok" }
                }
        }.onFailure { Log.w(TAG, "fetch: ${it.message}") }
        runCatching {
            c.supabase.fn("api", buildJsonObject {
                put("action", "phone_report"); put("target_id", id); put("status", status); put("ms", System.currentTimeMillis() - t0)
                put("items", buildJsonArray { cards.forEach { add(it) } })
                put("links", buildJsonArray { links.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            })
        }.onFailure { Log.w(TAG, "report: ${it.message}") }
        "$status ${cards.size}"
    }
}

/** Compact product data from a listing page (regex over the store's own card markup). */
object JumiaCards {
    private val CARD = Regex("""<article class="prd[\s\S]*?</article>""")
    private fun attr(s: String, name: String) = Regex("""$name="([^"]+)"""").find(s)?.groupValues?.get(1)
    private fun num(s: String?): Double? = s?.let { Regex("""\d[\d,]*(?:\.\d+)?""").find(it)?.value?.replace(",", "")?.toDoubleOrNull() }
    private fun clean(s: String) = s.replace(Regex("<[^>]+>"), " ").replace("&amp;", "&").replace("&quot;", "\"")
        .replace("&#39;", "'").replace(Regex("\\s+"), " ").trim()

    fun extract(html: String): List<JsonObject> = CARD.findAll(html).take(80).mapNotNull { m ->
        val a = m.value
        val href = Regex("""href="(/[^"#]+)"""").find(a)?.groupValues?.get(1) ?: return@mapNotNull null
        val sku = attr(a, "data-gtm-id") ?: attr(a, "data-ga4-item_id") ?: attr(a, "data-id")
            ?: Regex("""-(\d{5,})\.html""").find(href)?.groupValues?.get(1) ?: return@mapNotNull null
        val name = Regex("""<h3 class="name">([\s\S]*?)</h3>""").find(a)?.groupValues?.get(1)?.let(::clean) ?: attr(a, "data-gtm-name")
        val price = num(Regex("""class="prc"[^>]*>([^<]+)<""").find(a)?.groupValues?.get(1))
        val old = num(Regex("""class="old"[^>]*>([^<]+)<""").find(a)?.groupValues?.get(1))
        if (name.isNullOrBlank() || price == null || old == null || old <= price) return@mapNotNull null
        buildJsonObject {
            put("sku", sku); put("title", name); put("url", "https://www.jumia.com.eg$href"); put("price", price); put("old", old)
            attr(a, "data-src")?.let { put("image_url", it) }
        }
    }.toList()

    fun links(html: String): List<String> = Regex("""href="(/[a-z0-9-]+/)"""").findAll(html).map { it.groupValues[1] }.distinct().take(60).toList()
}
