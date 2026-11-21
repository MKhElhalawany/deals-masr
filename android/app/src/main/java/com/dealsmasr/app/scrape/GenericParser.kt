package com.dealsmasr.app.scrape

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Store-independent deal extractor. A "deal card" is the smallest block that contains a link, an image,
 * a crossed-out (old) price and a lower current price. Works on most shop layouts (noon, talabat mart, Jumia…)
 * and never invents a price: both prices must be present on the page.
 */
object GenericParser {
    private val strikeClass = Regex("""(old|was|strike|before|original|crossed|line-?through|prev|regular|rrp|list-?price)""", RegexOption.IGNORE_CASE)
    private val priceClass = Regex("""(price|prc|amount|now|sell|current|final|sale|value)""", RegexOption.IGNORE_CASE)
    private val currency = Regex("""(EGP|E£|LE|ج\.?\s?م|جنيه)""", RegexOption.IGNORE_CASE)

    private fun isStrike(e: Element): Boolean {
        if (e.tagName() in setOf("del", "s", "strike")) return true
        if (e.attr("data-a-strike") == "true") return true
        if (e.attr("style").replace(" ", "").contains("line-through")) return true
        return strikeClass.containsMatchIn(e.className()) || strikeClass.containsMatchIn(e.attr("data-qa"))
    }

    private fun priceOf(e: Element): Double? {
        val t = e.text()
        if (t.length > 40) return null
        return PriceText.parse(t)?.takeIf { it >= 1 }
    }

    fun parse(doc: Document, store: String, idFromUrl: (String) -> String = ::defaultId): List<ScrapedItem> {
        val out = LinkedHashMap<String, ScrapedItem>()
        val strikes = doc.body().select("*").filter { isStrike(it) && it.childrenSize() <= 3 && priceOf(it) != null }
        for (s in strikes) {
            val old = priceOf(s) ?: continue
            // climb to a container holding a link and an image
            var card: Element? = s.parent()
            var depth = 0
            while (card != null && depth < 10 && !(card.selectFirst("a[href]") != null && card.selectFirst("img") != null)) {
                card = card.parent(); depth++
            }
            if (card == null || card.text().length > 1500) continue
            val link = (if (card.tagName() == "a") card else card.selectFirst("a[href]"))?.absUrl("href")?.takeIf { it.startsWith("http") } ?: continue
            val current = card.select("*")
                .filter { e -> e !== s && !isStrike(e) && e.parents().none { isStrike(it) } && e.children().none { isStrike(it) } }
                .filter { e -> priceClass.containsMatchIn(e.className()) || priceClass.containsMatchIn(e.attr("data-qa")) || currency.containsMatchIn(e.ownText()) }
                .mapNotNull { priceOf(it) }
                .filter { it < old && it > old * 0.04 }
                .minOrNull() ?: continue
            val img = card.selectFirst("img")
            val image = img?.let { i ->
                listOf("data-src", "src", "data-lazy-src").map { i.absUrl(it) }.firstOrNull { it.startsWith("http") }
                    ?: i.attr("srcset").split(" ").firstOrNull { it.startsWith("http") }
            }
            val title = listOfNotNull(
                card.select("[class~=(?i)(title|name)], [data-qa*=name], [data-qa*=title], h2, h3, h4").map { it.text() }.maxByOrNull { it.length },
                img?.attr("alt"), card.selectFirst("a[title]")?.attr("title"),
            ).map { it.trim() }.firstOrNull { it.length >= 4 && PriceText.parse(it)?.let { p -> it.length > 12 } != false } ?: continue
            // Some shops (talabat) link every card to the same page, so the title is part of the id.
            val id = idFromUrl(link) + "-" + title.lowercase().hashCode().toUInt().toString(16)
            val item = ScrapedItem(store, id, title, link.substringBefore("#"), image, current, old, null, null)
            out.putIfAbsent(item.id, item)
        }
        return out.values.toList()
    }

    fun defaultId(url: String): String {
        val path = url.substringAfter("://").substringAfter('/').substringBefore('?').trimEnd('/')
        return path.split('/').lastOrNull { it.any(Char::isDigit) && it.length >= 4 } ?: path.hashCode().toUInt().toString(16)
    }
}
