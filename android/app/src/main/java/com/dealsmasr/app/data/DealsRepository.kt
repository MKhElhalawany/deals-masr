package com.dealsmasr.app.data

import com.dealsmasr.app.data.auth.Supabase
import com.dealsmasr.app.data.local.AlertDao
import com.dealsmasr.app.data.local.AlertEntity
import com.dealsmasr.app.data.local.DealDao
import com.dealsmasr.app.data.local.DealEntity
import com.dealsmasr.app.data.local.FavoriteDao
import com.dealsmasr.app.data.local.FavoriteEntity
import com.dealsmasr.app.data.local.toDto
import com.dealsmasr.app.data.local.toEntity
import com.dealsmasr.app.data.local.toFavorite
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.data.remote.DealPage
import com.dealsmasr.app.data.remote.MarketplaceDto
import com.dealsmasr.app.data.remote.PricePointDto
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.time.OffsetDateTime

data class SearchFilters(
    val stores: Set<String> = emptySet(),   // empty = all
    val minDiscount: Int? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val category: String? = null,
)

data class PriceDrop(val favorite: FavoriteEntity, val newPrice: Double)

/** A deal the user may want to hear about (new on the server since the last sync). */
data class DealEvent(val deal: DealDto, val kind: Kind, val previousPrice: Double?) {
    enum class Kind { NEW, PRICE_DROP }
}

data class ServerNotification(val id: Long, val title: String, val body: String, val dealId: String?, val pct: Double?)

/**
 * Pure client: every deal comes from the 24/7 server worker via Supabase. The phone never opens store websites.
 * Sync is incremental (rows changed since the last sync), so a refresh costs almost no data.
 */
class DealsRepository(
    private val supabase: Supabase,
    private val json: Json,
    private val settings: SettingsStore,
    private val dealDao: DealDao,
    private val favoriteDao: FavoriteDao,
    private val alertDao: AlertDao,
    private val interests: com.dealsmasr.app.core.Interests? = null,
) {
    val cachedDeals: Flow<List<DealEntity>> = dealDao.observeAll()
    val favorites: Flow<List<FavoriteEntity>> = favoriteDao.observeAll()
    val favoriteIds: Flow<List<String>> = favoriteDao.observeIds()
    val alerts: Flow<List<AlertEntity>> = alertDao.observeAll()

    private val storeNames = mapOf("amazon" to "أمازون مصر", "jumia" to "جوميا مصر", "noon" to "نون مصر", "townteam" to "تاون تيم", "talabat" to "طلبات")
    private var lastSyncIso: String? = null
    private var lastFullSync = 0L
    private var syncLoaded = false
    private val cols = "id,store_id,title,url,image_url,category,original_price,current_price,discount_pct,deal_pct,best_price,rating,rating_count,brand,seller,active,deal_since,last_checked,updated_at"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun epoch(iso: String?): Long = iso?.let { runCatching { OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() } ?: 0L
    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull

    private fun toDeal(o: JsonObject): DealDto {
        val orig = o.num("original_price") ?: 0.0
        val cur = o.num("current_price") ?: 0.0
        val store = o.str("store_id").orEmpty()
        return DealDto(
            id = o.str("id").orEmpty(), marketplace = store, marketplaceName = storeNames[store] ?: store,
            title = o.str("title").orEmpty(), imageUrl = o.str("image_url"), url = o.str("url").orEmpty(),
            category = o.str("category"), rating = o.num("rating"), ratingCount = (o["rating_count"] as? JsonPrimitive)?.intOrNull,
            originalPrice = orig, currentPrice = cur, discountPct = o.num("deal_pct") ?: o.num("discount_pct") ?: 0.0,
            savings = if (orig > cur) Math.round((orig - cur) * 100) / 100.0 else 0.0,
            bestPrice = o.num("best_price"), brand = o.str("brand"), seller = o.str("seller"),
            dealSince = epoch(o.str("deal_since")), lastChecked = epoch(o.str("last_checked")),
            active = (o["active"] as? JsonPrimitive)?.booleanOrNull ?: true,
        )
    }

    private fun rows(text: String): List<JsonObject> = json.parseToJsonElement(text).jsonArray.map { it.jsonObject }

    /**
     * Pulls server changes. A full snapshot only when this phone has no cache yet or once a day; otherwise only rows
     * changed since the last sync (position kept across app restarts). Returns deals that are new or cheaper than
     * what this phone had cached (for notifications).
     */
    suspend fun refresh(): List<DealEvent> {
        if (!syncLoaded) { settings.syncState().let { (c, f) -> lastSyncIso = c; lastFullSync = f }; syncLoaded = true }
        val before = dealDao.snapshot().associateBy { it.id }
        val now = System.currentTimeMillis()
        val full = lastSyncIso == null || before.isEmpty() || now - lastFullSync > 24 * 3_600_000L
        val list = ArrayList<JsonObject>()
        if (full) {
            list += rows(supabase.select("deals", "select=$cols&active=eq.true&order=deal_pct.desc.nullslast&limit=400"))
        } else {
            // gte (not gt): rows written in one batch share a timestamp; re-reading the boundary row is harmless
            var cursor = lastSyncIso!!
            for (page in 0 until 4) {
                val batch = rows(supabase.select("deals", "select=$cols&updated_at=gte.${enc(cursor)}&order=updated_at.asc&limit=500"))
                list += batch
                val last = batch.mapNotNull { it.str("updated_at") }.maxOrNull()
                if (batch.size < 500 || last == null || last == cursor) break
                cursor = last
            }
        }
        val deals = list.map { toDeal(it) }.distinctBy { it.id }
        if (full) {
            dealDao.replaceAll(deals.map { it.toEntity() }); lastFullSync = now
        } else {
            dealDao.upsertAll(deals.filter { it.active }.map { it.toEntity() })
            deals.filter { !it.active }.forEach { dealDao.delete(it.id) }
        }
        list.mapNotNull { it.str("updated_at") }.maxOrNull()?.let { if (lastSyncIso == null || it > lastSyncIso!!) lastSyncIso = it }
            ?: run { if (lastSyncIso == null) lastSyncIso = java.time.Instant.now().toString() }
        settings.setSyncState(lastSyncIso, lastFullSync)
        settings.setLastRefresh(now)
        if (before.isEmpty()) return emptyList() // first sync: don't announce the whole catalogue
        return deals.filter { it.active }.mapNotNull { d ->
            val old = before[d.id]
            when {
                old == null -> DealEvent(d, DealEvent.Kind.NEW, null)
                d.currentPrice < old.currentPrice - 0.5 -> DealEvent(d, DealEvent.Kind.PRICE_DROP, old.currentPrice)
                else -> null
            }
        }
    }

    /**
     * Applies one row pushed over Realtime straight to the local cache (no network round-trip).
     * Returns an event if it is a new deal or a price drop compared with what this phone had.
     */
    suspend fun applyRealtime(record: JsonObject): DealEvent? {
        val d = toDeal(record)
        if (d.id.isBlank() || d.id.contains(":ci-")) return null
        val old = dealDao.get(d.id)
        if (!d.active) { dealDao.delete(d.id); return null }
        dealDao.upsertAll(listOf(d.toEntity()))
        record.str("updated_at")?.let { u -> if (lastSyncIso == null || u > lastSyncIso!!) lastSyncIso = u }
        return when {
            old == null -> DealEvent(d, DealEvent.Kind.NEW, null)
            d.currentPrice < old.currentPrice - 0.5 -> DealEvent(d, DealEvent.Kind.PRICE_DROP, old.currentPrice)
            else -> null
        }
    }

    suspend fun refreshIfDue(maxAgeMillis: Long): List<DealEvent> =
        if (System.currentTimeMillis() - settings.current().lastRefresh >= maxAgeMillis) refresh() else emptyList()

    /** Live state of the last search: true while the server is still checking the stores for this query. */
    data class SearchResult(val page: DealPage, val live: Boolean, val english: String?)

    /**
     * Search through our API: Arabic or English, any words (Arabic is translated for the stores' English titles),
     * filters and sorting on the server. [askServer] lets the server also search the stores live for new queries.
     */
    suspend fun searchApi(query: String, filters: SearchFilters, sort: String, page: Int = 1, askServer: Boolean = false): SearchResult {
        if (page == 1 && query.isNotBlank()) runCatching { interests?.searched(query) }
        val body = buildJsonObject {
            put("action", "search"); put("q", query.trim().take(100)); put("page", page)
            put("sort", when (sort) { "price_asc", "price_desc", "newest" -> sort; else -> "discount" })
            if (filters.stores.isNotEmpty()) put("stores", buildJsonArray { filters.stores.forEach { add(JsonPrimitive(it)) } })
            filters.minDiscount?.takeIf { it > 0 }?.let { put("min_pct", it) }
            filters.minPrice?.let { put("min_price", it) }
            filters.maxPrice?.let { put("max_price", it) }
            if (!askServer) put("no_live", true)
        }
        val o = json.parseToJsonElement(supabase.fn("api", body)).jsonObject
        o.str("error")?.let { throw IllegalStateException(it) }
        val items = (o["items"]?.jsonArray ?: JsonArray(emptyList())).map { toDeal(it.jsonObject) }
        dealDao.upsertAll(items.filter { it.active }.map { it.toEntity() })
        val size = 30
        val more = (o["has_more"] as? JsonPrimitive)?.booleanOrNull == true
        return SearchResult(
            DealPage(total = (page - 1) * size + items.size + if (more) 1 else 0, page = page, pageSize = size, items = items,
                categories = items.mapNotNull { it.category }.distinct()),
            live = (o["live"] as? JsonPrimitive)?.booleanOrNull == true, english = o.str("english"))
    }

    // ───────── "يستاهل ولا لأ؟"
    data class Verdict(val verdict: String, val code: String, val score: Int?, val explanation: String, val ai: Boolean,
                       val lowest: Double?, val highest: Double?, val days: Double?, val observations: Int,
                       val cheaper: DealDto?, val cheaperStore: String?)

    suspend fun verify(id: String): Verdict {
        val o = json.parseToJsonElement(supabase.fn("api", buildJsonObject { put("action", "verify"); put("deal_id", id) })).jsonObject
        o.str("error")?.let { throw IllegalStateException(it) }
        val f = o["facts"] as? JsonObject
        val ch = o["cheaper"] as? JsonObject
        val cheaper = ch?.let {
            val st = it.str("store").orEmpty()
            DealDto(id = it.str("id").orEmpty(), marketplace = st, marketplaceName = storeNames[st] ?: st, title = it.str("title").orEmpty(),
                imageUrl = it.str("image_url"), url = it.str("url").orEmpty(), originalPrice = 0.0, currentPrice = it.num("price") ?: 0.0,
                discountPct = 0.0, savings = 0.0, lastChecked = 0)
        }
        return Verdict(o.str("verdict").orEmpty(), o.str("code").orEmpty(), (o["score"] as? JsonPrimitive)?.intOrNull,
            o.str("explanation").orEmpty(), (o["ai"] as? JsonPrimitive)?.booleanOrNull == true,
            f?.num("lowest_seen"), f?.num("highest_seen"), f?.num("days_tracked"), (f?.get("observations") as? JsonPrimitive)?.intOrNull ?: 0,
            cheaper, cheaper?.marketplaceName)
    }

    // ───────── assistant
    data class LiveOffer(val label: String, val title: String, val store: String, val price: Double, val url: String, val imageUrl: String?)
    data class ChatReply(val text: String, val products: List<DealDto>, val limited: Boolean, val live: List<LiveOffer> = emptyList())

    suspend fun chat(history: List<Pair<Boolean, String>>): ChatReply {
        val body = buildJsonObject {
            put("action", "chat")
            put("lang", settings.current().language)
            interests?.let { i -> runCatching { i.profile() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { put("profile", it) } }
            put("messages", buildJsonArray {
                history.takeLast(10).forEach { (mine, t) -> add(buildJsonObject { put("role", if (mine) "user" else "assistant"); put("text", t) }) }
            })
        }
        val o = json.parseToJsonElement(supabase.fn("api", body)).jsonObject
        o.str("error")?.let { throw IllegalStateException(it) }
        val products = (o["products"]?.jsonArray ?: JsonArray(emptyList())).map { toDeal(it.jsonObject) }
        val live = (o["live"]?.jsonArray ?: JsonArray(emptyList())).mapNotNull { e ->
            val x = e.jsonObject
            val url = x.str("url")?.takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            val store = x.str("store").orEmpty()
            LiveOffer(x.str("n").orEmpty(), x.str("title").orEmpty(), storeNames[store] ?: store, x.num("price") ?: return@mapNotNull null, url, x.str("image_url"))
        }
        return ChatReply(o.str("reply").orEmpty(), products, (o["limited"] as? JsonPrimitive)?.booleanOrNull == true, live)
    }

    suspend fun search(query: String, filters: SearchFilters, sort: String, page: Int = 1, askServer: Boolean = false): DealPage =
        searchApi(query, filters, sort, page, askServer).page

    suspend fun product(id: String): DealDto {
        val d = rows(supabase.select("deals", "select=$cols&id=eq.${enc(id)}")).firstOrNull()?.let { toDeal(it) }
            ?: dealDao.get(id)?.toDto() ?: throw IllegalStateException("العرض ده مبقاش متاح")
        val hist = runCatching {
            rows(supabase.select("price_history", "select=current_price,original_price,checked_at&deal_id=eq.${enc(id)}&order=checked_at.asc&limit=200"))
                .map { PricePointDto(it.num("original_price") ?: 0.0, it.num("current_price") ?: 0.0, epoch(it.str("checked_at"))) }
        }.getOrDefault(emptyList())
        runCatching { interests?.opened(d) }
        return d.copy(history = hist)
    }

    /** Deals of one side-menu category ("main" or "main/sub"), straight from the server (always current). */
    suspend fun categoryDeals(category: String, page: Int = 0, query: String = "", stores: Set<String> = emptySet(),
                              minPct: Int = 0, sort: String = "discount"): List<DealDto> {
        val f = mutableListOf(if ('/' in category) "category=eq.${enc(category)}" else "category=like.${enc("$category/*")}")
        query.trim().split(Regex("\\s+")).filter { it.length >= 2 }.take(3).forEach { w ->
            f += "title=ilike.${enc("*" + w.replace(Regex("[(),*]"), "") + "*")}"
        }
        if (stores.isNotEmpty()) f += "store_id=in.(${stores.joinToString(",")})"
        if (minPct > 0) f += "deal_pct=gte.$minPct"
        val order = when (sort) { "price_asc" -> "current_price.asc"; "price_desc" -> "current_price.desc"; "newest" -> "deal_since.desc"; else -> "deal_pct.desc.nullslast" }
        return rows(supabase.select("deals", "select=$cols&active=eq.true&${f.joinToString("&")}&order=$order&limit=60&offset=${page * 60}"))
            .map { toDeal(it) }
    }

    data class TgPost(val channel: String, val id: Long, val text: String, val url: String?, val imageUrl: String?, val price: Double?,
                      val oldPrice: Double?, val postedAt: Long, val postedIso: String?) {
        val key get() = "$channel/$id"
    }

    /** "عروض مطرقعة": posts of the deal channels the server follows, newest first (all channels mixed). */
    suspend fun crazyPosts(beforeIso: String? = null, keywords: List<String> = emptyList()): List<TgPost> =
        rows(supabase.select("tg_posts", "select=channel,post_id,text,url,image_url,price,old_price,posted_at" +
            "&posted_at=not.is.null" + (beforeIso?.let { "&posted_at=lt.${enc(it)}" } ?: "") +
            (if (keywords.isEmpty()) "" else "&or=(" + keywords.joinToString(",") { "text.ilike.*${enc(it)}*" } + ")") +
            "&order=posted_at.desc&limit=40")).map {
            TgPost(it.str("channel").orEmpty(), (it["post_id"] as? JsonPrimitive)?.longOrNull ?: 0, it.str("text").orEmpty(), it.str("url"),
                it.str("image_url"), it.num("price"), it.num("old_price"), epoch(it.str("posted_at")), it.str("posted_at"))
        }

    /** Health of the server worker per store (admin screen). */
    suspend fun marketplaces(): List<MarketplaceDto> =
        rows(supabase.select("scraper_status", "select=store,ok,message,last_run&order=store.asc")).map {
            val store = it.str("store").orEmpty()
            MarketplaceDto(store, when (store) { "worker" -> "سيرفر Oracle"; "edge" -> "سيرفر Supabase"; else -> storeNames[store] ?: store }, true,
                (it["ok"] as? JsonPrimitive)?.booleanOrNull ?: false, it.str("message").orEmpty(), epoch(it.str("last_run")))
        }

    // ───────── favourites (synced so the server can notify about price drops)
    suspend fun toggleFavorite(d: DealDto, isFavorite: Boolean) {
        if (isFavorite) {
            favoriteDao.delete(d.id)
            runCatching { supabase.delete("favorites", "deal_id=eq.${enc(d.id)}") }
        } else {
            runCatching { interests?.favorited(d) }
            favoriteDao.upsert(d.toFavorite(System.currentTimeMillis()))
            runCatching { supabase.insert("favorites", buildJsonObject { put("deal_id", d.id) }) }
        }
    }

    suspend fun removeFavorite(id: String) {
        favoriteDao.delete(id)
        runCatching { supabase.delete("favorites", "deal_id=eq.${enc(id)}") }
    }

    /** Refreshes cached favourites from the server; returns price drops. */
    suspend fun refreshFavorites(): List<PriceDrop> {
        val favs = favoriteDao.all()
        if (favs.isEmpty()) return emptyList()
        val ids = favs.joinToString(",") { "\"" + it.productId.replace("\"", "") + "\"" }
        val fresh = rows(supabase.select("deals", "select=$cols&id=in.(${enc(ids)})")).map { toDeal(it) }.associateBy { it.id }
        val drops = mutableListOf<PriceDrop>()
        favs.forEach { f ->
            val d = fresh[f.productId]
            if (d == null) favoriteDao.upsert(f.copy(active = false))
            else {
                if (d.active && d.currentPrice < f.currentPrice) drops += PriceDrop(f, d.currentPrice)
                favoriteDao.upsert(d.toFavorite(f.addedAt))
            }
        }
        return drops
    }

    // ───────── alerts (matched by the server against every new deal, even while the phone is off)
    suspend fun addAlert(query: String, minDiscount: Int, stores: Set<String>, maxPrice: Double?) {
        supabase.insert("alerts", buildJsonObject {
            put("query", query.trim()); put("min_discount", minDiscount)
            if (stores.isNotEmpty()) put("stores", buildJsonArray { stores.forEach { add(JsonPrimitive(it)) } })
            maxPrice?.let { put("max_price", it) }
        })
        alertDao.insert(AlertEntity(query = query.trim(), minDiscount = minDiscount,
            stores = stores.takeIf { it.isNotEmpty() }?.joinToString(","), maxPrice = maxPrice, createdAt = System.currentTimeMillis()))
    }

    suspend fun deleteAlert(a: AlertEntity) {
        alertDao.delete(a)
        runCatching { supabase.delete("alerts", "query=eq.${enc(a.query)}&min_discount=eq.${a.minDiscount}") }
    }

    /** Unseen per-user notifications the server created (alert matches, favourite price drops); marks them seen. */
    suspend fun serverNotifications(): List<ServerNotification> {
        val list = rows(supabase.select("user_notifications", "select=id,title,body,deal_id,min_pct&seen=eq.false&order=created_at.asc&limit=20"))
            .map { ServerNotification((it["id"] as JsonPrimitive).content.toLong(), it.str("title").orEmpty(), it.str("body").orEmpty(), it.str("deal_id"), it.num("min_pct")) }
        if (list.isNotEmpty()) runCatching {
            supabase.update("user_notifications", "id=in.(${list.joinToString(",") { it.id.toString() }})", buildJsonObject { put("seen", true) })
        }
        return list
    }
}
