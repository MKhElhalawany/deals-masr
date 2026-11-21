package com.dealsmasr.app.data.auth

import com.dealsmasr.app.core.DealMath
import com.dealsmasr.app.data.SettingsStore
import com.dealsmasr.app.data.remote.ConfigDto
import com.dealsmasr.app.data.remote.SheinPricingDto
import com.dealsmasr.app.data.remote.TierDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class Profile(
    val id: String,
    @SerialName("full_name") val fullName: String? = null,
    val phone: String? = null,
    val role: String = "user",
) {
    val isAdmin get() = role == "admin"
}

sealed interface AuthState {
    data object Loading : AuthState
    data object NotConfigured : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val profile: Profile, val email: String?) : AuthState
}

/** Owns the signed-in state; loads the user's profile (role) and the shared app config after login. */
class AuthRepository(
    private val supabase: Supabase,
    private val store: SessionStore,
    private val settings: SettingsStore,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Loading)
    val state: StateFlow<AuthState> = _state

    val isSignedIn get() = supabase.session.value != null

    init {
        when {
            !SupabaseConfig.configured -> _state.value = AuthState.NotConfigured
            supabase.session.value == null -> _state.value = AuthState.SignedOut
            else -> {
                // Show the app immediately from the cached profile; refresh it in the background.
                cachedProfile()?.let { _state.value = AuthState.SignedIn(it, supabase.session.value?.email) }
                scope.launch { afterLogin() }
            }
        }
        // Session dropped elsewhere (refresh token revoked) → back to login.
        scope.launch {
            supabase.session.collect { if (it == null && _state.value is AuthState.SignedIn) _state.value = AuthState.SignedOut }
        }
    }

    private fun cachedProfile(): Profile? =
        store.loadProfileJson()?.let { runCatching { json.decodeFromString(Profile.serializer(), it) }.getOrNull() }

    private suspend fun afterLogin() {
        val s = supabase.session.value ?: return
        try {
            val p = json.decodeFromString(ListSerializer(Profile.serializer()), supabase.select("profiles", "id=eq.${s.userId}&select=id,full_name,phone,role"))
                .firstOrNull() ?: Profile(s.userId)
            store.saveProfileJson(json.encodeToString(Profile.serializer(), p))
            settings.setAdminMode(p.isAdmin)
            if (p.fullName != null || p.phone != null) {
                val cur = settings.current()
                if (cur.customerName.isBlank()) settings.saveCustomer(p.fullName.orEmpty(), p.phone.orEmpty(), cur.customerAddress)
            }
            _state.value = AuthState.SignedIn(p, s.email)
            loadConfig()
        } catch (e: SupabaseException) {
            if (supabase.session.value == null) _state.value = AuthState.SignedOut
            else if (_state.value !is AuthState.SignedIn) _state.value = AuthState.SignedIn(cachedProfile() ?: Profile(s.userId), s.email)
        }
    }

    /** One request for all shared settings (pricing formula, WhatsApp number, delivery window). */
    suspend fun loadConfig() {
        val rows = json.parseToJsonElement(supabase.select("app_config", "select=key,value")).let { it as kotlinx.serialization.json.JsonArray }
        val map: Map<String, JsonElement> = rows.associate { r -> r.jsonObject["key"]!!.jsonPrimitive.content to r.jsonObject["value"]!! }
        val pricing = map["shein_pricing"]?.jsonObject
        val days = map["order_days"]?.jsonObject
        settings.saveConfig(
            ConfigDto(
                tiers = DealMath.DEFAULT_TIERS.map { TierDto(it.key, it.labelAr, it.minPct) },
                minDiscountPct = map["min_discount_pct"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 10.0,
                sheinPricing = SheinPricingDto(
                    pricing?.get("sar_to_egp")?.jsonPrimitive?.content ?: "13.5",
                    pricing?.get("multiplier")?.jsonPrimitive?.content ?: "1.20",
                    pricing?.get("delivery_fee_egp")?.jsonPrimitive?.content ?: "130",
                ),
                orderCountdownDays = days?.get("max")?.jsonPrimitive?.intOrNull ?: 35,
                whatsappNumber = map["whatsapp_number"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
        )
    }

    suspend fun signIn(email: String, password: String) {
        supabase.signIn(email.trim(), password)
        afterLogin()
    }

    /** @return true if signed in, false if an e-mail confirmation is required first. */
    suspend fun signUp(email: String, password: String, fullName: String, phone: String): Boolean {
        val signedIn = supabase.signUp(email.trim(), password, fullName.trim(), phone.trim())
        if (signedIn) afterLogin()
        return signedIn
    }

    suspend fun resetPassword(email: String) = supabase.resetPassword(email.trim())

    suspend fun sendOwnerCode(email: String) = supabase.sendOtp(email.trim())

    /** Code login; grants admin only if this is the owner's e-mail (checked by the database). */
    suspend fun ownerSignIn(email: String, code: String): Boolean {
        supabase.verifyOtp(email.trim(), code.trim())
        val isOwner = runCatching { supabase.rpc("claim_owner").trim() == "true" }.getOrDefault(false)
        afterLogin()
        return isOwner
    }

    suspend fun signOut() {
        supabase.signOut()
        settings.setAdminMode(false)
        _state.value = AuthState.SignedOut
    }
}
