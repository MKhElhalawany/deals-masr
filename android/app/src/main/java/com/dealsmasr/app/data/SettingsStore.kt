package com.dealsmasr.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dealsmasr.app.BuildConfig
import com.dealsmasr.app.core.DealMath
import com.dealsmasr.app.core.DealTier
import com.dealsmasr.app.core.OrderCountdown
import com.dealsmasr.app.core.SheinPricingConfig
import com.dealsmasr.app.data.remote.ConfigDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore("settings")

/** Orders are sent to this WhatsApp number (shop owner). */
const val OWNER_WHATSAPP = "201050038728"

val DEFAULT_KEYWORDS = listOf(
    "air fryer", "smart watch", "headphones", "laptop", "mobile phone", "perfume", "vacuum cleaner",
    "blender", "coffee maker", "television", "sneakers", "power bank",
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val serverUrl: String,
    val theme: ThemeMode,
    val notificationsEnabled: Boolean,
    val majorDealAlerts: Boolean,
    val customerName: String,
    val customerPhone: String,
    val customerAddress: String,
    val lastRefresh: Long,
    val config: ConfigDto?,
    val scrapeKeywords: List<String>,
    val scrapeStores: Set<String>,
    val scrapeStatus: Map<String, String>,
    val extraPages: List<String>,
    val liveScan: Boolean,
    val liveIntervalSec: Int,
    val liveWifiOnly: Boolean,
    val notifyMinPct: Int,
    val adminMode: Boolean,
    val celebrated: Set<String>,
) {
    val hasServer get() = serverUrl.isNotBlank()
    val tiers: List<DealTier>
        get() = config?.tiers?.map { DealTier(it.key, it.labelAr, it.minPct) } ?: DealMath.DEFAULT_TIERS
    val pricing: SheinPricingConfig
        get() = config?.sheinPricing?.let { SheinPricingConfig.of(it.sarToEgp, it.multiplier, it.deliveryFeeEgp) }
            ?: SheinPricingConfig()
    val countdownDays get() = config?.orderCountdownDays ?: OrderCountdown.DEFAULT_DAYS
    val whatsappNumber get() = config?.whatsappNumber?.takeIf { it.isNotBlank() } ?: OWNER_WHATSAPP
}

class SettingsStore(private val context: Context, private val json: Json) {
    private object K {
        val server = stringPreferencesKey("server_url")
        val theme = stringPreferencesKey("theme")
        val notif = booleanPreferencesKey("notifications")
        val major = booleanPreferencesKey("major_deals")
        val name = stringPreferencesKey("customer_name")
        val phone = stringPreferencesKey("customer_phone")
        val address = stringPreferencesKey("customer_address")
        val lastRefresh = longPreferencesKey("last_refresh")
        val lastNotif = longPreferencesKey("last_notification_ts")
        val config = stringPreferencesKey("config_json")
        val keywords = stringPreferencesKey("scrape_keywords")
        val stores = stringPreferencesKey("scrape_stores")
        val status = stringPreferencesKey("scrape_status")
        val cursor = longPreferencesKey("scrape_cursor")
        val alertCheck = longPreferencesKey("alerts_last_check")
        val extraPages = stringPreferencesKey("extra_pages")
        val live = booleanPreferencesKey("live_scan")
        val liveInterval = intPreferencesKey("live_interval")
        val wifiOnly = booleanPreferencesKey("live_wifi_only")
        val notifyMin = intPreferencesKey("notify_min_pct")
        val admin = booleanPreferencesKey("admin_mode")
        val celebrated = stringPreferencesKey("celebrated_orders")
        val adminSeen = stringPreferencesKey("admin_last_order_seen")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p -> p.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    private fun Preferences.toSettings() = AppSettings(
        serverUrl = this[K.server] ?: BuildConfig.DEFAULT_API_URL,
        theme = runCatching { ThemeMode.valueOf(this[K.theme] ?: "SYSTEM") }.getOrDefault(ThemeMode.SYSTEM),
        notificationsEnabled = this[K.notif] ?: true,
        majorDealAlerts = this[K.major] ?: true,
        customerName = this[K.name].orEmpty(),
        customerPhone = this[K.phone].orEmpty(),
        customerAddress = this[K.address].orEmpty(),
        lastRefresh = this[K.lastRefresh] ?: 0L,
        config = this[K.config]?.let { runCatching { json.decodeFromString(ConfigDto.serializer(), it) }.getOrNull() },
        scrapeKeywords = (this[K.keywords] ?: DEFAULT_KEYWORDS.joinToString("\n")).split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() },
        scrapeStores = (this[K.stores] ?: "amazon,jumia,noon").split(",").filter { it.isNotBlank() }.toSet(),
        scrapeStatus = (this[K.status] ?: "").split("\n").mapNotNull { l -> l.split("|", limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap(),
        liveScan = this[K.live] ?: false,
        liveIntervalSec = this[K.liveInterval] ?: 20,
        liveWifiOnly = this[K.wifiOnly] ?: false,
        notifyMinPct = this[K.notifyMin] ?: 10,
        adminMode = this[K.admin] ?: false,
        celebrated = (this[K.celebrated] ?: "").split(",").filter { it.isNotBlank() }.toSet(),
        extraPages = (this[K.extraPages] ?: "").split("\n").map { it.trim() }.filter { it.startsWith("http") },
    )

    suspend fun setServerUrl(v: String) = context.dataStore.edit { it[K.server] = v.trim() }
    suspend fun setTheme(v: ThemeMode) = context.dataStore.edit { it[K.theme] = v.name }
    suspend fun setNotifications(v: Boolean) = context.dataStore.edit { it[K.notif] = v }
    suspend fun setMajorDeals(v: Boolean) = context.dataStore.edit { it[K.major] = v }
    suspend fun setLastRefresh(v: Long) = context.dataStore.edit { it[K.lastRefresh] = v }
    suspend fun saveConfig(c: ConfigDto) = context.dataStore.edit { it[K.config] = json.encodeToString(ConfigDto.serializer(), c) }
    suspend fun saveCustomer(name: String, phone: String, address: String) = context.dataStore.edit {
        it[K.name] = name; it[K.phone] = phone; it[K.address] = address
    }
    suspend fun setScrapeKeywords(v: String) = context.dataStore.edit { it[K.keywords] = v }
    suspend fun lastAdminOrderSeen(): String = context.dataStore.data.first()[K.adminSeen].orEmpty()
    suspend fun setLastAdminOrderSeen(v: String) = context.dataStore.edit { it[K.adminSeen] = v }
    suspend fun setAdminMode(v: Boolean) = context.dataStore.edit { it[K.admin] = v }
    suspend fun markCelebrated(orderId: String) = context.dataStore.edit {
        it[K.celebrated] = ((it[K.celebrated] ?: "").split(",").filter { x -> x.isNotBlank() } + orderId).takeLast(200).joinToString(",")
    }
    suspend fun setLiveScan(v: Boolean) = context.dataStore.edit { it[K.live] = v }
    suspend fun setLiveInterval(v: Int) = context.dataStore.edit { it[K.liveInterval] = v }
    suspend fun setLiveWifiOnly(v: Boolean) = context.dataStore.edit { it[K.wifiOnly] = v }
    suspend fun setNotifyMinPct(v: Int) = context.dataStore.edit { it[K.notifyMin] = v }
    suspend fun setExtraPages(v: String) = context.dataStore.edit { it[K.extraPages] = v }
    suspend fun setScrapeStores(v: Set<String>) = context.dataStore.edit { it[K.stores] = v.joinToString(",") }
    suspend fun setScrapeStatus(store: String, text: String) = context.dataStore.edit { p ->
        val cur = (p[K.status] ?: "").split("\n").filter { it.isNotBlank() && !it.startsWith("$store|") }
        p[K.status] = (cur + "$store|${text.replace("\n", " ")}").joinToString("\n")
    }
    /** Returns the current keyword cursor and advances it by [step]. */
    suspend fun nextKeywordCursor(step: Int): Long {
        var value = 0L
        context.dataStore.edit { p -> value = p[K.cursor] ?: 0L; p[K.cursor] = value + step }
        return value
    }
    suspend fun lastAlertCheck(): Long = context.dataStore.data.first()[K.alertCheck] ?: 0L
    suspend fun setLastAlertCheck(v: Long) = context.dataStore.edit { it[K.alertCheck] = v }

    suspend fun lastNotificationTs(): Long = context.dataStore.data.first()[K.lastNotif] ?: 0L
    suspend fun setLastNotificationTs(v: Long) = context.dataStore.edit { it[K.lastNotif] = v }
}
