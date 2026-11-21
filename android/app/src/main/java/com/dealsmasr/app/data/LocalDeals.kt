package com.dealsmasr.app.data

import com.dealsmasr.app.core.DealMath
import com.dealsmasr.app.core.OrderCountdown
import com.dealsmasr.app.data.local.DealDao
import com.dealsmasr.app.data.local.DealEntity
import com.dealsmasr.app.data.local.PricePointEntity
import com.dealsmasr.app.data.local.toDto
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.data.remote.DealPage
import com.dealsmasr.app.data.remote.MarketplaceDto
import com.dealsmasr.app.data.remote.PricePointDto
import com.dealsmasr.app.scrape.PageFetcher
import com.dealsmasr.app.scrape.SCRAPERS
import com.dealsmasr.app.scrape.ScrapeResult
import com.dealsmasr.app.scrape.ScrapedItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Serverless mode: reads Amazon.eg / Jumia public listing pages directly from the phone.
 * Every price shown is exactly what the store page displayed at [DealEntity.lastChecked].
 */
class LocalDeals(private val settings: SettingsStore, private val dealDao: DealDao) {
    private val fetcher = PageFetcher()
    private val mutex = Mutex()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun valid(i: ScrapedItem, minDiscount: Double): Double? {
        val pct = i.discountPct ?: return null
        return pct.takeIf { it >= minDiscount && it <= 95.0 }
    }

    /** Validates, stores (with price history) and returns (entity, isNew) pairs. */
    private suspend fun save(items: List<ScrapedItem>, minDiscount: Double): List<Pair<DealEntity, Boolean>> {
        val good = items.mapNotNull { i -> valid(i, minDiscount)?.let { i to it } }.distinctBy { it.first.id }
        if (good.isEmpty()) return emptyList()
        val now = nowSec()
        val existing = dealDao.getAll(good.map { it.first.id }).associateBy { it.id }
        val points = mutableListOf<PricePointEntity>()
        val out = good.map { (i, pct) ->
            val old = existing[i.id]
            val changed = old == null || old.currentPrice != i.currentPrice || old.originalPrice != i.originalPrice
            if (changed) points += PricePointEntity(i.id, i.currentPrice, i.originalPrice, now)
            DealEntity(
                id = i.id, marketplace = i.store, marketplaceName = SCRAPERS[i.store]?.nameAr ?: i.store,
                title = i.title, imageUrl = i.imageUrl ?: old?.imageUrl, url = i.url, category = old?.category,
                rating = i.rating ?: old?.rating, ratingCount = i.ratingCount ?: old?.ratingCount,
                originalPrice = i.originalPrice, currentPrice = i.currentPrice, discountPct = pct,
                savings = DealMath.savings(i.originalPrice, i.currentPrice),
                dealSince = old?.dealSince ?: now, lastChecked = now,
            ) to (old == null)
        }
        dealDao.upsertAll(out.map { it.first })
        dealDao.addPoints(points)
        return out
    }

    private suspend fun status(store: String, text: String) {
        val t = timeFmt.format(Instant.now().atZone(OrderCountdown.CAIRO))
        settings.setScrapeStatus(store, "$text · $t")
    }

    /**
     * Reads the next [keywordsPerRun] keywords (rotating) from each enabled store.
     * Returns newly discovered deals. Throws only if every store failed.
     */
    suspend fun refresh(keywordsPerRun: Int = 3): List<DealDto> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = settings.current()
            val kws = s.scrapeKeywords
            val stores = s.scrapeStores.mapNotNull { SCRAPERS[it] }
            if (kws.isEmpty() || stores.isEmpty()) return@withContext emptyList()
            val n = minOf(keywordsPerRun, kws.size)
            val start = settings.nextKeywordCursor(n)
            val batch = (0 until n).map { kws[((start + it) % kws.size).toInt()] }
            val fresh = mutableListOf<DealDto>()
            val errors = mutableListOf<String>()
            var anyOk = false
            for (st in stores) {
                var deals = 0; var cards = 0; var problem: String? = null
                for (kw in batch) {
                    when (val r = fetcher.fetch(st, st.searchUrl(kw, 1))) {
                        is ScrapeResult.Ok -> {
                            cards += r.cardsSeen
                            val saved = save(r.items, s.minDiscount())
                            deals += saved.size
                            fresh += saved.filter { it.second }.map { it.first.toDto() }
                        }
                        is ScrapeResult.Blocked -> { problem = r.reason; break }
                        is ScrapeResult.Failed -> problem = r.reason
                    }
                }
                when {
                    problem != null && cards == 0 -> { status(st.id, "⚠️ $problem"); errors += "${st.nameAr}: $problem" }
                    cards == 0 -> { status(st.id, "⚠️ الصفحة اتقرت بس مفيهاش منتجات (ممكن شكل الموقع اتغير)"); errors += "${st.nameAr}: 0 منتجات" }
                    else -> { anyOk = true; status(st.id, "✓ $deals عرض من $cards منتج (${batch.joinToString("، ")})") }
                }
            }
            dealDao.pruneOlderThan(nowSec() - 48 * 3600)
            dealDao.prunePoints()
            settings.setLastRefresh(System.currentTimeMillis())
            if (!anyOk) throw IllegalStateException(errors.joinToString("\n"))
            fresh
        }
    }

    suspend fun search(query: String, filters: SearchFilters, sort: String, page: Int): DealPage = withContext(Dispatchers.IO) {
        val s = settings.current()
        val wanted = filters.stores.ifEmpty { setOf("amazon", "jumia", "noon") }
        val scrapers = wanted.mapNotNull { SCRAPERS[it] }
        val unavailable = wanted.filter { it !in SCRAPERS }
        val minD = filters.minDiscount?.toDouble()?.takeIf { it > 0 } ?: s.minDiscount()
        val results = mutableListOf<DealDto>()
        val errors = mutableListOf<String>()
        var hasMore = false
        for (st in scrapers) {
            when (val r = fetcher.fetch(st, st.searchUrl(query.trim(), page))) {
                is ScrapeResult.Ok -> {
                    if (r.cardsSeen >= 10) hasMore = true
                    results += save(r.items, minD).map { it.first.toDto() }
                }
                is ScrapeResult.Blocked -> errors += "${st.nameAr}: ${r.reason}"
                is ScrapeResult.Failed -> errors += "${st.nameAr}: ${r.reason}"
            }
        }
        if (results.isEmpty() && errors.size == scrapers.size && scrapers.isNotEmpty()) {
            throw IllegalStateException(errors.joinToString("\n"))
        }
        val filtered = results.filter { d ->
            (filters.minPrice == null || d.currentPrice >= filters.minPrice) &&
                (filters.maxPrice == null || d.currentPrice <= filters.maxPrice)
        }
        val sorted = DealMath.sort(filtered, com.dealsmasr.app.core.DealSort.entries.firstOrNull { it.apiKey == sort } ?: com.dealsmasr.app.core.DealSort.DISCOUNT)
        DealPage(
            total = sorted.size + if (hasMore) 1 else 0, page = page, pageSize = sorted.size, items = sorted,
            storesUnavailable = unavailable + errors.map { it.substringBefore(":") }.mapNotNull { n -> SCRAPERS.values.firstOrNull { it.nameAr == n }?.id },
        )
    }

    suspend fun product(id: String): DealDto {
        val e = dealDao.get(id) ?: throw IllegalStateException("العرض ده مبقاش متاح — اعمل تحديث")
        val hist = dealDao.history(id).map { PricePointDto(it.original, it.price, it.checkedAt) }
        return e.toDto().copy(history = hist)
    }

    suspend fun marketplaces(): List<MarketplaceDto> {
        val s = settings.current()
        return listOf("amazon" to "أمازون مصر", "jumia" to "جوميا مصر", "noon" to "نون مصر").map { (id, name) ->
            MarketplaceDto(id, name, enabled = id in s.scrapeStores, configured = id in SCRAPERS,
                status = s.scrapeStatus[id] ?: if (id in SCRAPERS) "لسه ماتقراش" else "غير مدعوم في وضع القراءة المباشرة")
        }
    }

    suspend fun cached(ids: List<String>) = dealDao.getAll(ids)
}

private fun AppSettings.minDiscount(): Double = config?.minDiscountPct ?: 10.0
