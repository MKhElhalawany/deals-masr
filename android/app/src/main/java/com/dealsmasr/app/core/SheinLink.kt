package com.dealsmasr.app.core

import java.net.URI
import java.net.URLDecoder

/** Mirrors backend/app/providers/shein.py extract_goods_id. */
object SheinLink {
    private val patterns = listOf(
        Regex("""-p-(\d{5,})(?:-cat-\d+)?\.html""", RegexOption.IGNORE_CASE),
        Regex("""/goods[-_/]?(?:detail/)?(\d{5,})""", RegexOption.IGNORE_CASE),
        Regex("""[?&](?:goods_id|goodsId|productId)=(\d{5,})""", RegexOption.IGNORE_CASE),
    )
    private val urlRegex = Regex("""https?://\S+""")

    /** Pulls the first URL out of shared text such as "Check this out! https://...". */
    fun firstUrl(text: String): String? = urlRegex.find(text)?.value?.trimEnd('.', ',', ')')

    fun extractGoodsId(url: String): String? {
        val u = url.trim()
        for (p in patterns) p.find(u)?.let { return it.groupValues[1] }
        val query = runCatching { URI(u).rawQuery }.getOrNull() ?: return null
        for (part in query.split('&')) {
            val (k, v) = part.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            if (k in setOf("url", "link", "redirect")) {
                extractGoodsId(URLDecoder.decode(v, "UTF-8"))?.let { return it }
            }
        }
        return null
    }

    fun isSheinUrl(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        return host.contains("shein.") || host == "shein.top"
    }
}
