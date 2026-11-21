package com.dealsmasr.app.scrape

import com.dealsmasr.app.core.SheinLink
import org.jsoup.Jsoup

data class SheinProduct(
    val goodsId: String,
    val title: String,
    val imageUrl: String?,
    /** Highest ORIGINAL (retail, before discount) price across all sizes/colours of this item, in SAR. */
    val retailSar: Double,
    val pricesSeen: Int,
    val url: String,
)

sealed interface SheinPriceResult {
    data class Ok(val product: SheinProduct) : SheinPriceResult
    data class Failed(val reason: String, val html: String?) : SheinPriceResult
}

/**
 * Reads a SHEIN product page and extracts the retail (pre-discount) SAR price. SHEIN embeds the product data
 * (including every SKU/size price) as JSON in the page; we scope the search to the JSON object of this
 * goods_id so recommended products on the same page are never mixed in, then take the highest retail price.
 */
object SheinPageParser {
    private val retailAmount = Regex(""""retail(?:Price|_price)"\s*:\s*\{[^{}]*?"amount"\s*:\s*"?([\d.]+)"?""")
    private val retailWithSymbol = Regex(""""retail(?:Price|_price)"\s*:\s*\{[^{}]*?"amountWithSymbol"\s*:\s*"([^"]*)"""")
    private val goodsName = Regex(""""goods_name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
    private val goodsImg = Regex(""""goods_img"\s*:\s*"([^"]+)"""")
    private val egp = Regex("""(EGP|E£|ج\.?\s?م|جنيه)""")

    /** Smallest balanced {...} block that encloses [index]. */
    private fun enclosingObject(s: String, index: Int, minLen: Int): IntRange? {
        var depth = 0
        var start = -1
        var i = index
        while (i >= 0) {
            when (s[i]) {
                '}' -> depth++
                '{' -> if (depth == 0) { start = i; break } else depth--
            }
            i--
        }
        if (start < 0) return null
        depth = 0
        var j = start
        var inStr = false
        while (j < s.length) {
            val ch = s[j]
            if (ch == '"' && s[j - 1] != '\\') inStr = !inStr
            if (!inStr) {
                if (ch == '{') depth++
                if (ch == '}') { depth--; if (depth == 0) break }
            }
            j++
        }
        if (j >= s.length) return null
        val range = start..j
        // grow outwards until the block is big enough to contain the SKU list
        if (range.last - range.first >= minLen || start == 0) return range
        // Grow outwards, but never into a page-wide state object that also holds recommended products.
        val parent = enclosingObject(s, start - 1, minLen) ?: return range
        return if (parent.last - parent.first > 150_000) range else parent
    }

    fun parse(html: String, goodsId: String, url: String): SheinPriceResult {
        val idPattern = Regex(""""goods_id"\s*:\s*"?$goodsId"?""")
        val prices = mutableListOf<Double>()
        var title: String? = null
        var image: String? = null
        var wrongCurrency = false
        for (m in idPattern.findAll(html).take(40)) {
            val r = enclosingObject(html, m.range.first, 1500) ?: continue
            val block = html.substring(r)
            retailWithSymbol.findAll(block).forEach { if (egp.containsMatchIn(it.groupValues[1])) wrongCurrency = true }
            prices += retailAmount.findAll(block).mapNotNull { it.groupValues[1].toDoubleOrNull() }.filter { it > 0 }
            if (title == null) title = goodsName.find(block)?.groupValues?.get(1)?.let { unescape(it) }
            if (image == null) image = goodsImg.find(block)?.groupValues?.get(1)?.let { if (it.startsWith("//")) "https:$it" else it }
        }
        if (wrongCurrency) return SheinPriceResult.Failed("الصفحة فتحت بأسعار مصر مش السعودية", html)
        val doc = Jsoup.parse(html, url)
        if (title == null) title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
        if (image == null) image = doc.selectFirst("meta[property=og:image]")?.attr("content")
        if (prices.isEmpty()) {
            // Fallback: crossed-out price shown in the main product area.
            val text = doc.select(".product-intro__head-price del, .product-intro__head-price .from, [class*=retail], del")
                .mapNotNull { PriceText.parse(it.text()) }
            prices += text.filter { it > 0 }
        }
        val max = prices.maxOrNull() ?: return SheinPriceResult.Failed("مالقيناش السعر الأصلي في الصفحة", html)
        return SheinPriceResult.Ok(SheinProduct(goodsId, title ?: "منتج SHEIN $goodsId", image, max, prices.size, url))
    }

    private fun unescape(s: String) = s.replace("\\\"", "\"").replace("\\/", "/").replace(Regex("""\\u([0-9a-fA-F]{4})""")) {
        it.groupValues[1].toInt(16).toChar().toString()
    }

    /** All product ids on a bag/cart/share page. */
    fun goodsIdsOnPage(html: String): List<String> {
        val ids = LinkedHashSet<String>()
        Regex("""-p-(\d{5,})""").findAll(html).forEach { ids += it.groupValues[1] }
        Regex(""""goods_id"\s*:\s*"?(\d{5,})""").findAll(html).forEach { ids += it.groupValues[1] }
        return ids.toList()
    }
}

/** Opens SHEIN pages in the hidden browser (Saudi storefront) and prices them. */
class SheinPricer(private val web: WebFetcher) {
    var lastHtml: String? = null
        private set

    private val storefronts = listOf("https://sa.shein.com", "https://ar.shein.com")

    /** Resolves share/short links to a goods id by opening them. */
    suspend fun goodsIdOf(link: String): String? {
        SheinLink.extractGoodsId(link)?.let { return it }
        val (finalUrl, html) = web.renderWithUrl(link, settleMs = 2500) ?: return null
        lastHtml = html
        return SheinLink.extractGoodsId(finalUrl) ?: SheinPageParser.goodsIdsOnPage(html).firstOrNull()
    }

    suspend fun price(goodsId: String): SheinPriceResult {
        var last: SheinPriceResult = SheinPriceResult.Failed("تعذر فتح صفحة المنتج", null)
        for (base in storefronts) {
            val url = "$base/product-p-$goodsId.html?currency=SAR"
            val html = web.render(url, settleMs = 4000, timeoutMs = 40_000) ?: continue
            lastHtml = html
            last = SheinPageParser.parse(html, goodsId, "$base/product-p-$goodsId.html")
            if (last is SheinPriceResult.Ok) return last
        }
        return last
    }

    /** A shared bag: open it, collect every product on it, price each one. */
    suspend fun bag(link: String): Pair<List<SheinProduct>, List<String>> {
        val (_, html) = web.renderWithUrl(link, settleMs = 4000, timeoutMs = 40_000) ?: return emptyList<SheinProduct>() to emptyList()
        lastHtml = html
        val ids = SheinPageParser.goodsIdsOnPage(html).take(40)
        val ok = mutableListOf<SheinProduct>()
        val failed = mutableListOf<String>()
        for (id in ids) when (val r = price(id)) {
            is SheinPriceResult.Ok -> ok += r.product
            is SheinPriceResult.Failed -> failed += id
        }
        return ok to failed
    }
}
