package com.dealsmasr.app.data

import com.dealsmasr.app.data.local.AlertDao
import com.dealsmasr.app.data.local.AlertEntity
import com.dealsmasr.app.data.local.DealDao
import com.dealsmasr.app.data.local.DealEntity
import com.dealsmasr.app.data.local.FavoriteDao
import com.dealsmasr.app.data.local.FavoriteEntity
import com.dealsmasr.app.data.local.toEntity
import com.dealsmasr.app.data.local.toFavorite
import com.dealsmasr.app.data.remote.ApiProvider
import com.dealsmasr.app.data.remote.DealDto
import com.dealsmasr.app.data.remote.DealPage
import com.dealsmasr.app.data.remote.DealsApi
import com.dealsmasr.app.data.remote.MarketplaceDto
import com.dealsmasr.app.data.remote.NotificationDto
import kotlinx.coroutines.flow.Flow

data class SearchFilters(
    val stores: Set<String> = emptySet(),   // empty = all
    val minDiscount: Int? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val category: String? = null,
)

data class PriceDrop(val favorite: FavoriteEntity, val newPrice: Double)

class DealsRepository(
    private val apiProvider: ApiProvider,
    private val settings: SettingsStore,
    private val dealDao: DealDao,
    private val favoriteDao: FavoriteDao,
    private val alertDao: AlertDao,
) {
    val cachedDeals: Flow<List<DealEntity>> = dealDao.observeAll()
    val favorites: Flow<List<FavoriteEntity>> = favoriteDao.observeAll()
    val favoriteIds: Flow<List<String>> = favoriteDao.observeIds()
    val alerts: Flow<List<AlertEntity>> = alertDao.observeAll()

    private suspend fun api(): DealsApi = apiProvider.get(settings.current().serverUrl)

    /** Refresh config + the deal snapshot (max [pages] × 50 deals, highest discount first). */
    suspend fun refresh(pages: Int = 4) {
        val api = api()
        runCatching { settings.saveConfig(api.config()) }
        val all = mutableListOf<DealDto>()
        for (p in 1..pages) {
            val page = api.deals(page = p, pageSize = 50)
            all += page.items
            if (all.size >= page.total || page.items.isEmpty()) break
        }
        dealDao.replaceAll(all.distinctBy { it.id }.map { it.toEntity() })
        settings.setLastRefresh(System.currentTimeMillis())
    }

    suspend fun search(query: String, filters: SearchFilters, sort: String, page: Int = 1): DealPage =
        api().search(
            q = query.trim(),
            stores = filters.stores.takeIf { it.isNotEmpty() }?.joinToString(","),
            minDiscount = filters.minDiscount?.takeIf { it > 0 },
            minPrice = filters.minPrice,
            maxPrice = filters.maxPrice,
            category = filters.category,
            sort = sort,
            page = page,
        )

    suspend fun product(id: String): DealDto = api().product(id)
    suspend fun marketplaces(): List<MarketplaceDto> = api().marketplaces()

    suspend fun toggleFavorite(d: DealDto, isFavorite: Boolean) {
        if (isFavorite) favoriteDao.delete(d.id) else favoriteDao.upsert(d.toFavorite(System.currentTimeMillis()))
    }

    suspend fun removeFavorite(id: String) = favoriteDao.delete(id)

    /** Re-verifies favourites against the backend; returns real price drops. */
    suspend fun refreshFavorites(): List<PriceDrop> {
        val favs = favoriteDao.all()
        if (favs.isEmpty()) return emptyList()
        val drops = mutableListOf<PriceDrop>()
        favs.chunked(100).forEach { chunk ->
            val fresh = api().products(chunk.joinToString(",") { it.productId }).associateBy { it.id }
            chunk.forEach { f ->
                val d = fresh[f.productId]
                if (d == null) {
                    favoriteDao.upsert(f.copy(active = false))
                } else {
                    if (d.active && d.currentPrice < f.currentPrice) drops += PriceDrop(f, d.currentPrice)
                    favoriteDao.upsert(d.toFavorite(f.addedAt))
                }
            }
        }
        return drops
    }

    suspend fun addAlert(query: String, minDiscount: Int, stores: Set<String>, maxPrice: Double?) {
        alertDao.insert(
            AlertEntity(query = query.trim(), minDiscount = minDiscount,
                stores = stores.takeIf { it.isNotEmpty() }?.joinToString(","), maxPrice = maxPrice,
                createdAt = System.currentTimeMillis())
        )
    }

    suspend fun deleteAlert(a: AlertEntity) = alertDao.delete(a)

    /** Runs every alert as a search; returns (alert, new matching deals) pairs. */
    suspend fun checkAlerts(): List<Pair<AlertEntity, List<DealDto>>> {
        val out = mutableListOf<Pair<AlertEntity, List<DealDto>>>()
        for (a in alertDao.all()) {
            val res = runCatching {
                search(a.query, SearchFilters(a.stores?.split(",")?.toSet() ?: emptySet(), a.minDiscount, null, a.maxPrice, null), "discount")
            }.getOrNull() ?: continue
            val seen = a.notifiedIds.split(",").filter { it.isNotBlank() }.toSet()
            val fresh = res.items.filter { it.id !in seen && it.discountPct >= a.minDiscount }
            if (fresh.isNotEmpty()) {
                out += a to fresh
                alertDao.update(a.copy(notifiedIds = (seen + fresh.map { it.id }).toList().takeLast(300).joinToString(",")))
            }
        }
        return out
    }

    suspend fun majorDealNotifications(): List<NotificationDto> {
        val since = settings.lastNotificationTs()
        val list = api().notifications(since)
        list.maxOfOrNull { it.createdAt }?.let { settings.setLastNotificationTs(it) }
        // First run: don't flood the user with historical notifications.
        return if (since == 0L) emptyList() else list
    }
}
