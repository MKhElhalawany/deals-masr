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
import android.content.Context
import com.dealsmasr.app.scrape.CustomPageScraper
import com.dealsmasr.app.scrape.PageFetcher
import com.dealsmasr.app.scrape.STORE_NAMES
import com.dealsmasr.app.scrape.StoreScraper
import com.dealsmasr.app.scrape.WebFetcher
import java.io.File
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

/** Something the user may want to hear about: a newly seen deal or a lower price on a known one. */
data class DealEvent(val deal: DealDto, val kind: Kind, val previousPrice: Double?) {
    enum class Kind { NEW, PRICE_DROP }
}

/** One page the live monitor reads. */
data class ScanTarget(val scraper: StoreScraper, val url: String, val label: String)

/**
 * Serverless mode: reads Amazon.eg / Jumia public listing pages directly from the phone.
 * Every price shown is exactly what the store page displayed at [DealEntity.lastChecked].
 */
class LocalDeals(private val context: Context, private val settings: SettingsStore, private val dealDao: DealDao) {
    private val fetcher = PageFetcher(WebFetcher(context))
    private val mutex = Mutex()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun valid(i: ScrapedItem, minDiscount: Double): Double? {
        val pct = i.discountPct ?: return null
        return pct.takeIf { it >= minDiscount && it <= 95.0 }
    }

    /** Validates, stores (with price history) and returns each saved deal with its event (if any). */
    private suspend fun save(items: List<ScrapedItem>, minDiscount: Double): List<Pair<DealEntity, DealEvent?>> {
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
                id = i.id, marketplace = i.store, marketplaceName = STORE_NAMES[i.store] ?: i.store,
                title = i.title, imageUrl = i.imageUrl ?: old?.imageUrl, url = i.url, category = old?.category,
                rating = i.rating ?: old?.rating, ratingCount = i.ratingCount ?: old?.ratingCount,
                originalPrice = i.originalPrice, currentPrice = i.currentPrice, discountPct = pct,
                savings = DealMath.savings(i.originalPrice, i.currentPrice),
                dealSince = old?.dealSince ?: now, lastChecked = now,
            ).let { e ->
                val event = when {
                    old == null -> DealEvent(e.toDto(), DealEvent.Kind.NEW, null)
                    i.currentPrice < old.currentPrice - 0.5 -> DealEvent(e.toDto(), DealEvent.Kind.PRICE_DROP, old.currentPrice)
                    else -> null
                }
                e to event
            }
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
    suspend fun refresh(keywordsPerRun: Int = 3): List<DealEvent> = mutex.withLock {
        withContext(Dispatchers.IO) {
            val s = settings.current()
            val kws = s.scrapeKeywords
            val stores = s.scrapeStores.mapNotNull { SCRAPERS[it] }
            val extra = s.extraPages.map { CustomPageScraper(it) }
            if ((kws.isEmpty() || stores.isEmpty()) && extra.isEmpty()) return@withContext emptyList()
            val n = minOf(keywordsPerRun, kws.size)
            val start = if (n > 0) settings.nextKeywordCursor(n) else 0L
            val batch = (0 until n).map { kws[((start + it) % kws.size).toInt()] }
            val fresh = mutableListOf<DealEvent>()
            val errors = mutableListOf<String>()
            var anyOk = false
            val jobs: List<Pair<StoreScraper, List<String>>> =
                stores.map { st -> st to batch.map { st.searchUrl(it, 1) } } + extra.map { it to listOf(it.searchUrl("", 1)) }
            for ((st, urls) in jobs) {
                var deals = 0; var cards = 0; var problem: String? = null
                for (url in urls) {
                    when (val r = fetcher.fetchSmart(st, url)) {
                        is ScrapeResult.Ok -> {
                            cards += r.cardsSeen
                            val saved = save(r.items, s.minDiscount())
                            deals += saved.size
                            fresh += saved.mapNotNull { it.second }
                        }
                        is ScrapeResult.Blocked -> { problem = r.reason; break }
                        is ScrapeResult.Failed -> problem = r.reason
                    }
                }
                when {
                    problem != null && cards == 0 -> { status(st.id, "⚠️ $problem"); errors += "${st.nameAr}: $problem" }
                    cards == 0 -> { status(st.id, "⚠️ الصفحة اتقرت بس مفيهاش منتجات (ممكن شكل الموقع اتغير)"); errors += "${st.nameAr}: 0 منتجات" }
                    else -> { anyOk = true; status(st.id, "✓ $deals عرض من $cards منتج" + if (st is CustomPageScraper) " (صفحة مضافة)" else " (${batch.joinToString("، ")})") }
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
            when (val r = fetcher.fetchSmart(st, st.searchUrl(query.trim(), page))) {
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

    /** Pages the live monitor cycles through: keyword searches per store, store deal pages, user pages. */
    suspend fun liveTargets(): List<ScanTarget> {
        val s = settings.current()
        val perStore = s.scrapeStores.mapNotNull { SCRAPERS[it] }.map { st ->
            st.dealPages.map { ScanTarget(st, it, "${st.nameAr} · صفحة العروض") } +
                s.scrapeKeywords.map { kw -> ScanTarget(st, st.searchUrl(kw, 1), "${st.nameAr} · $kw") }
        }
        // interleave stores so each one is hit as rarely as possible
        val mixed = mutableListOf<ScanTarget>()
        val maxLen = perStore.maxOfOrNull { it.size } ?: 0
        for (i in 0 until maxLen) perStore.forEach { list -> list.getOrNull(i)?.let { mixed += it } }
        return mixed + s.extraPages.map { val c = CustomPageScraper(it); ScanTarget(c, it, "${c.nameAr} · صفحة مضافة") }
    }

    /** Reads one target, saves deals, returns events. Throws [StoreBlockedException] when the store blocks us. */
    suspend fun scan(t: ScanTarget): List<DealEvent> = withContext(Dispatchers.IO) {
        val s = settings.current()
        when (val r = fetcher.fetchSmart(t.scraper, t.url)) {
            is ScrapeResult.Ok -> {
                val saved = save(r.items, s.minDiscount())
                settings.setLastRefresh(System.currentTimeMillis())
                status(t.scraper.id, "✓ ${saved.size} عرض من ${r.cardsSeen} منتج (${t.label.substringAfter("· ")})")
                saved.mapNotNull { it.second }
            }
            is ScrapeResult.Blocked -> { status(t.scraper.id, "⚠️ ${r.reason}"); throw StoreBlockedException(t.scraper.id) }
            is ScrapeResult.Failed -> { status(t.scraper.id, "⚠️ ${r.reason}"); emptyList() }
        }
    }

    suspend fun prune() {
        dealDao.pruneOlderThan(nowSec() - 48 * 3600)
        dealDao.prunePoints()
    }

    /** Fetches one page for [store] and saves its raw HTML so the user can share it for troubleshooting. */
    suspend fun diagnosticPage(store: String): File = withContext(Dispatchers.IO) {
        val s = settings.current()
        val scraper = SCRAPERS[store] ?: s.extraPages.map { CustomPageScraper(it) }.firstOrNull { it.id == store }
            ?: throw IllegalArgumentException(store)
        val kw = s.scrapeKeywords.firstOrNull() ?: "air fryer"
        val url = scraper.searchUrl(kw, 1)
        val r = fetcher.fetchSmart(scraper, url)
        val dir = File(context.cacheDir, "diag").apply { mkdirs() }
        File(dir, "$store-page.txt").apply {
            writeText("URL: $url\nRESULT: $r\n\n" + (fetcher.lastHtml[scraper.id] ?: "(no html)"))
        }
    }
}

private fun AppSettings.minDiscount(): Double = config?.minDiscountPct ?: 10.0

class StoreBlockedException(val store: String) : Exception(store)
