package com.dealsmasr.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
) {
    val hasServer get() = serverUrl.isNotBlank()
    val tiers: List<DealTier>
        get() = config?.tiers?.map { DealTier(it.key, it.labelAr, it.minPct) } ?: DealMath.DEFAULT_TIERS
    val pricing: SheinPricingConfig
        get() = config?.sheinPricing?.let { SheinPricingConfig.of(it.sarToEgp, it.multiplier, it.deliveryFeeEgp) }
            ?: SheinPricingConfig()
    val countdownDays get() = config?.orderCountdownDays ?: OrderCountdown.DEFAULT_DAYS
    val whatsappNumber get() = config?.whatsappNumber.orEmpty()
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
    suspend fun lastNotificationTs(): Long = context.dataStore.data.first()[K.lastNotif] ?: 0L
    suspend fun setLastNotificationTs(v: Long) = context.dataStore.edit { it[K.lastNotif] = v }
}
