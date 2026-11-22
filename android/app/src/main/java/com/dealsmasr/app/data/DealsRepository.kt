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
) {
    val cachedDeals: Flow<List<DealEntity>> = dealDao.observeAll()
    val favorites: Flow<List<FavoriteEntity>> = favoriteDao.observeAll()
    val favoriteIds: Flow<List<String>> = favoriteDao.observeIds()
    val alerts: Flow<List<AlertEntity>> = alertDao.observeAll()

    private val storeNames = mapOf("amazon" to "أمازون مصر", "jumia" to "جوميا مصر", "noon" to "نون مصر", "talabat" to "طلبات")
    private var lastSyncIso: String? = null
    private var lastFullSync = 0L
    private var syncLoaded = false
    private val cols = "id,store_id,title,url,image_url,category,original_price,current_price,discount_pct,rating,rating_count,active,deal_since,last_checked,updated_at"

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
            originalPrice = orig, currentPrice = cur, discountPct = o.num("discount_pct") ?: 0.0,
            savings = Math.round((orig - cur) * 100) / 100.0,
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
            list += rows(supabase.select("deals", "select=$cols&active=eq.true&order=discount_pct.desc&limit=400"))
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

    /** Searches the server's deal database; [askServer] also queues a fresh store search on the worker. */
    suspend fun search(query: String, filters: SearchFilters, sort: String, page: Int = 1, askServer: Boolean = false): DealPage {
        if (askServer && page == 1 && query.trim().length >= 2) runCatching {
            supabase.insert("search_requests", buildJsonObject {
                put("query", query.trim().take(80))
                if (filters.stores.isNotEmpty()) put("stores", buildJsonArray { filters.stores.forEach { add(JsonPrimitive(it)) } })
            })
        }
        val q = StringBuilder("select=$cols&active=eq.true")
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(5)
        if (words.isNotEmpty()) q.append("&and=(").append(words.joinToString(",") { "title.ilike.*${enc(it.replace(",", " ").replace("(", "").replace(")", ""))}*" }).append(")")
        if (filters.stores.isNotEmpty()) q.append("&store_id=in.(").append(filters.stores.joinToString(",")).append(")")
        (filters.minDiscount?.takeIf { it > 0 } ?: settings.current().config?.minDiscountPct?.toInt())?.let { q.append("&discount_pct=gte.$it") }
        filters.minPrice?.let { q.append("&current_price=gte.$it") }
        filters.maxPrice?.let { q.append("&current_price=lte.$it") }
        filters.category?.let { q.append("&category=eq.${enc(it)}") }
        val order = when (sort) {
            "price_asc" -> "current_price.asc"; "price_desc" -> "current_price.desc"; "newest" -> "deal_since.desc"
            "popular" -> "rating_count.desc.nullslast"; else -> "discount_pct.desc"
        }
        val size = 30
        q.append("&order=$order&limit=${size + 1}&offset=${(page - 1) * size}")
        val items = rows(supabase.select("deals", q.toString())).map { toDeal(it) }
        dealDao.upsertAll(items.map { it.toEntity() })
        val pageItems = items.take(size)
        return DealPage(total = (page - 1) * size + pageItems.size + if (items.size > size) 1 else 0, page = page,
            pageSize = size, items = pageItems, categories = pageItems.mapNotNull { it.category }.distinct())
    }

    suspend fun product(id: String): DealDto {
        val d = rows(supabase.select("deals", "select=$cols&id=eq.${enc(id)}")).firstOrNull()?.let { toDeal(it) }
            ?: dealDao.get(id)?.toDto() ?: throw IllegalStateException("العرض ده مبقاش متاح")
        val hist = runCatching {
            rows(supabase.select("price_history", "select=current_price,original_price,checked_at&deal_id=eq.${enc(id)}&order=checked_at.asc&limit=200"))
                .map { PricePointDto(it.num("original_price") ?: 0.0, it.num("current_price") ?: 0.0, epoch(it.str("checked_at"))) }
        }.getOrDefault(emptyList())
        return d.copy(history = hist)
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
