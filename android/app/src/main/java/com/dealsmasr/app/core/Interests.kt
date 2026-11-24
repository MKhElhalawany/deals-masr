package com.dealsmasr.app.core

import com.dealsmasr.app.data.SettingsStore
import com.dealsmasr.app.data.remote.DealDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * On-device learning of what this user likes: every opened product, favourite and search moves weights for its
 * category, store, brand and key words. Old interests fade (decay) so the model keeps adapting. Stays on the phone;
 * only a short summary is sent with a chatbot question so the answers are personal.
 */
class Interests(private val settings: SettingsStore, private val json: Json) {
    private val lock = Mutex()
    private var w: MutableMap<String, Double>? = null
    private val stop = setOf("with", "from", "for", "and", "the", "new", "pack", "size", "color", "colour", "black", "white",
        "men", "women", "set", "pcs", "piece", "model", "original", "egypt", "edition", "version", "inch", "plus", "pro")

    private suspend fun load(): MutableMap<String, Double> = w ?: runCatching {
        json.parseToJsonElement(settings.interests()).jsonObject.mapValues { it.value.jsonPrimitive.double }.toMutableMap()
    }.getOrDefault(mutableMapOf()).also { w = it }

    private suspend fun save(m: Map<String, Double>) = settings.setInterests(JsonObject(m.mapValues { JsonPrimitive(Math.round(it.value * 1000) / 1000.0) }).toString())

    private fun keywords(title: String) = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length >= 4 && it !in stop && !it.all(Char::isDigit) }.distinct().take(4)

    private fun features(d: DealDto): List<String> = buildList {
        d.category?.let { add("cat:$it"); add("main:${it.substringBefore('/')}") }
        add("store:${d.marketplace}")
        d.brand?.lowercase()?.let { add("brand:$it") }
        keywords(d.title).forEach { add("kw:$it") }
        add("price:" + when { d.currentPrice < 300 -> "low"; d.currentPrice < 2000 -> "mid"; d.currentPrice < 10000 -> "high"; else -> "premium" })
    }

    private suspend fun bump(keys: List<String>, amount: Double) = lock.withLock {
        val m = load()
        m.replaceAll { _, v -> v * 0.985 }   // forget slowly
        keys.forEach { m[it] = (m[it] ?: 0.0) + amount }
        m.entries.removeAll { it.value < 0.05 }
        if (m.size > 400) m.entries.sortedBy { it.value }.take(m.size - 400).forEach { m.remove(it.key) }
        save(m)
    }

    suspend fun opened(d: DealDto) = bump(features(d), 1.0)
    suspend fun favorited(d: DealDto) = bump(features(d), 3.0)
    suspend fun searched(q: String) = bump(keywords(q).map { "kw:$it" }, 2.0)

    suspend fun hasData() = lock.withLock { load().isNotEmpty() }

    /** Deals ranked by what this user tends to open, with discount as a tie-breaker. */
    suspend fun recommend(deals: List<DealDto>, n: Int): List<DealDto> {
        val m = lock.withLock { HashMap(load()) }
        if (m.isEmpty()) return emptyList()
        return deals.asSequence().map { d -> d to features(d).sumOf { m[it] ?: 0.0 } }
            .filter { it.second > 0.5 }
            .sortedByDescending { it.second + it.first.discountPct / 100.0 }
            .map { it.first }.take(n).toList()
    }

    /** Short readable summary for the chatbot, e.g. "kw:airfryer, cat:أجهزة منزلية/صغيرة, price:mid". */
    suspend fun profile(): String = lock.withLock { load().entries.sortedByDescending { it.value }.take(10).joinToString(", ") { it.key } }
}
