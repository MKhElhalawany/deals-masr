package com.dealsmasr.app.scrape

import com.dealsmasr.app.core.DealMath
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A product read from a store's public listing page. Prices are exactly what the page shows. */
data class ScrapedItem(
    val store: String,
    val externalId: String,
    val title: String,
    val url: String,
    val imageUrl: String?,
    val currentPrice: Double,
    val originalPrice: Double,
    val rating: Double?,
    val ratingCount: Int?,
) {
    val id get() = "$store:$externalId"
    val discountPct get() = DealMath.discountPercent(originalPrice, currentPrice)
}

sealed interface ScrapeResult {
    data class Ok(val items: List<ScrapedItem>, val cardsSeen: Int) : ScrapeResult
    data class Blocked(val reason: String) : ScrapeResult
    data class Failed(val reason: String) : ScrapeResult
}

object PriceText {
    private val num = Regex("""\d[\d,]*(?:\.\d+)?""")
    fun parse(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val norm = buildString {
            for (ch in s) append(
                when (ch) {
                    in '٠'..'٩' -> '0' + (ch - '٠')
                    in '۰'..'۹' -> '0' + (ch - '۰')
                    '٬' -> ','
                    '٫' -> '.'
                    else -> ch
                }
            )
        }
        return num.find(norm)?.value?.replace(",", "")?.toDoubleOrNull()
    }
}

interface StoreScraper {
    val id: String
    val nameAr: String
    /** true = page needs JavaScript, always load it in the hidden browser. */
    val needsBrowser: Boolean get() = false
    fun searchUrl(query: String, page: Int): String
    fun parse(doc: Document): ScrapeResult
}

/** noon Egypt: client-rendered, so read via WebView and the generic card detector. */
object NoonScraper : StoreScraper {
    override val id = "noon"
    override val nameAr = "نون مصر"
    override val needsBrowser = true
    override fun searchUrl(query: String, page: Int) =
        "https://www.noon.com/egypt-ar/search/?q=${URLEncoder.encode(query, "UTF-8").replace("+", "%20")}" + if (page > 1) "&page=$page" else ""

    override fun parse(doc: Document): ScrapeResult {
        val cards = doc.select("a[href*=/p/]").size
        val items = GenericParser.parse(doc, id) { url ->
            // /egypt-ar/<slug>/<SKU>/p/?o=…  →  SKU
            url.substringBefore("?").trimEnd('/').split('/').let { seg -> seg.getOrNull(seg.lastIndexOf("p") - 1) } ?: GenericParser.defaultId(url)
        }
        if (cards == 0 && doc.text().contains("Access Denied", true)) return ScrapeResult.Blocked("نون رفض الطلب")
        return ScrapeResult.Ok(items, maxOf(cards, items.size))
    }
}

/** Any page the user adds (talabat mart, offers pages…): hidden browser + generic card detector. */
class CustomPageScraper(private val pageUrl: String) : StoreScraper {
    private val host = runCatching { java.net.URI(pageUrl).host.orEmpty().removePrefix("www.") }.getOrDefault("")
    override val id = when {
        host.contains("talabat") -> "talabat"
        host.contains("noon") -> "noon"
        host.contains("amazon") -> "amazon"
        host.contains("jumia") -> "jumia"
        else -> host.substringBefore('.').ifBlank { "web" }
    }
    override val nameAr = STORE_NAMES[id] ?: host
    override val needsBrowser = true
    override fun searchUrl(query: String, page: Int) = pageUrl
    override fun parse(doc: Document): ScrapeResult {
        val items = GenericParser.parse(doc, id)
        return ScrapeResult.Ok(items, maxOf(items.size, doc.select("img").size / 2))
    }
}

val STORE_NAMES = mapOf("amazon" to "أمازون مصر", "jumia" to "جوميا مصر", "noon" to "نون مصر", "talabat" to "طلبات")

/** Jumia Egypt public catalogue pages (server-rendered `article.prd` cards). */
object JumiaScraper : StoreScraper {
    override val id = "jumia"
    override val nameAr = "جوميا مصر"
    private val skuInUrl = Regex("""-(\d{5,})\.html""")

    override fun searchUrl(query: String, page: Int) =
        "https://www.jumia.com.eg/catalog/?q=${URLEncoder.encode(query, "UTF-8")}" + if (page > 1) "&page=$page" else ""

    override fun parse(doc: Document): ScrapeResult {
        val title = doc.title().lowercase()
        if (title.contains("access denied") || title.contains("attention required") || doc.select("article.prd").isEmpty() &&
            doc.text().contains("captcha", ignoreCase = true)) return ScrapeResult.Blocked("جوميا رفض الطلب")
        val cards = doc.select("article.prd")
        val items = cards.mapNotNull { card(it) }.ifEmpty { GenericParser.parse(doc, id) }
        return ScrapeResult.Ok(items, maxOf(cards.size, items.size))
    }

    private fun card(c: Element): ScrapedItem? {
        val a = c.selectFirst("a.core") ?: c.selectFirst("a[href]") ?: return null
        val url = a.absUrl("href").substringBefore("#").ifBlank { return null }
        val name = c.selectFirst(".name")?.text()?.trim().orEmpty()
            .ifBlank { a.attr("data-gtm-name").ifBlank { a.attr("data-ga4-item_name") } }
        if (name.isBlank()) return null
        val current = PriceText.parse(c.selectFirst(".prc")?.text()) ?: return null
        val old = PriceText.parse(c.selectFirst(".old")?.text()) ?: return null // no strike price = not a verifiable deal
        val sku = a.attr("data-gtm-id").ifBlank { a.attr("data-id") }.ifBlank { a.attr("data-ga4-item_id") }
            .ifBlank { skuInUrl.find(url)?.groupValues?.get(1).orEmpty() }.ifBlank { url.substringAfterLast('/').substringBefore('.') }
        val img = c.selectFirst("img.img") ?: c.selectFirst("img")
        val image = img?.let { i -> i.absUrl("data-src").ifBlank { i.absUrl("src") } }?.takeIf { it.startsWith("http") }
        val rating = PriceText.parse(c.selectFirst(".stars._s")?.text())?.takeIf { it in 0.0..5.0 }
        val count = PriceText.parse(c.selectFirst(".rev")?.ownText())?.toInt()
        return ScrapedItem(id, sku, name, url, image, current, old, rating, count)
    }
}

/** Amazon Egypt public search results (`s-search-result` cards, strike price = `.a-text-price`). */
object AmazonScraper : StoreScraper {
    override val id = "amazon"
    override val nameAr = "أمازون مصر"

    /** pct-off asks Amazon itself to return only discounted items. */
    override fun searchUrl(query: String, page: Int) =
        "https://www.amazon.eg/s?k=${URLEncoder.encode(query, "UTF-8")}&pct-off=10-&language=en_AE" +
            if (page > 1) "&page=$page" else ""

    override fun parse(doc: Document): ScrapeResult {
        val html = doc.outerHtml()
        if (doc.selectFirst("form[action*=validateCaptcha]") != null || html.contains("Type the characters you see") ||
            html.contains("api-services-support@amazon.com")) return ScrapeResult.Blocked("أمازون طلب تحقق (كابتشا)")
        val cards = doc.select("[data-component-type=s-search-result]")
        val items = cards.mapNotNull { card(it) }.ifEmpty { GenericParser.parse(doc, id) }
        return ScrapeResult.Ok(items, maxOf(cards.size, items.size))
    }

    private fun card(c: Element): ScrapedItem? {
        val asin = c.attr("data-asin").ifBlank { return null }
        val title = (c.selectFirst("h2")?.attr("aria-label")?.takeIf { it.isNotBlank() } ?: c.selectFirst("h2")?.text())
            ?.trim().orEmpty()
        if (title.isBlank()) return null
        val current = PriceText.parse(c.selectFirst(".a-price:not(.a-text-price) .a-offscreen")?.text()) ?: return null
        val old = PriceText.parse(c.selectFirst(".a-price.a-text-price .a-offscreen")?.text())
            ?: PriceText.parse(c.selectFirst("[data-a-strike=true] .a-offscreen")?.text()) ?: return null
        val image = c.selectFirst("img.s-image")?.absUrl("src")?.takeIf { it.startsWith("http") }
        val rating = PriceText.parse(c.selectFirst("span.a-icon-alt")?.text())?.takeIf { it in 0.0..5.0 }
        val count = c.select("a[href*=customerReviews] span, span.s-underline-text").firstNotNullOfOrNull {
            PriceText.parse(it.text().replace("K", "000"))?.toInt()
        }
        return ScrapedItem(id, asin, title, "https://www.amazon.eg/dp/$asin", image, current, old, rating, count)
    }
}

/** Fetches store pages like a normal mobile browser, with cookies and a polite delay between requests. */
class PageFetcher(private val web: WebFetcher? = null) {
    /** Last raw HTML per store (for the "share page" diagnostic). */
    val lastHtml = ConcurrentHashMap<String, String>()

    /** Plain HTTP first; hidden browser if the page needs JS, was blocked, or showed no products. */
    suspend fun fetchSmart(scraper: StoreScraper, url: String): ScrapeResult {
        if (!scraper.needsBrowser) {
            val r = fetch(scraper, url)
            if (r is ScrapeResult.Ok && r.cardsSeen > 0) return r
            if (web == null) return r
        }
        val html = web?.render(url) ?: return ScrapeResult.Failed("المتصفح الداخلي ماقدرش يفتح الصفحة")
        lastHtml[scraper.id] = html
        return runCatching { scraper.parse(Jsoup.parse(html, url)) }.getOrElse { ScrapeResult.Failed(it.message ?: "parse error") }
    }

    private val cookies = ConcurrentHashMap<String, List<Cookie>>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val old = this@PageFetcher.cookies[url.host].orEmpty().filter { o -> cookies.none { it.name == o.name } }
                this@PageFetcher.cookies[url.host] = old + cookies
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies[url.host].orEmpty()
        })
        .build()

    @Volatile private var lastRequest = 0L

    fun fetch(scraper: StoreScraper, url: String): ScrapeResult {
        val wait = 1500 - (System.currentTimeMillis() - lastRequest)
        if (wait > 0) Thread.sleep(wait)
        lastRequest = System.currentTimeMillis()
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "ar-EG,ar;q=0.9,en-US;q=0.8,en;q=0.7")
            .build()
        return try {
            http.newCall(req).execute().use { r ->
                when {
                    r.code == 403 || r.code == 503 || r.code == 429 -> ScrapeResult.Blocked("الموقع رفض الطلب (${r.code})")
                    !r.isSuccessful -> ScrapeResult.Failed("HTTP ${r.code}")
                    else -> {
                        val html = r.body?.string().orEmpty()
                        lastHtml[scraper.id] = html
                        scraper.parse(Jsoup.parse(html, r.request.url.toString()))
                    }
                }
            }
        } catch (e: Exception) {
            ScrapeResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}

val SCRAPERS: Map<String, StoreScraper> = listOf(AmazonScraper, JumiaScraper, NoonScraper).associateBy { it.id }
