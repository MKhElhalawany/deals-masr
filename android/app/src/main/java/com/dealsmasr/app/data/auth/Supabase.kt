package com.dealsmasr.app.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dealsmasr.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Project URL + public (anon / publishable) key, injected at build time from Gradle properties
 * `supabaseUrl` / `supabaseAnonKey` (GitHub repository variables in CI). The public key is designed to ship
 * inside apps; all data protection comes from Row Level Security in supabase/migrations.
 */
object SupabaseConfig {
    val url: String = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String = BuildConfig.SUPABASE_ANON_KEY
    val configured get() = url.startsWith("https://") && anonKey.isNotBlank()
}

@Serializable
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,          // epoch seconds
    val userId: String,
    val email: String? = null,
)

const val APP_REDIRECT = "dealsmasr://login"

class SupabaseException(val status: Int, override val message: String) : Exception(message)

/** Tokens live only in Keystore-encrypted prefs; passwords are never stored. */
class SessionStore(context: Context, private val json: Json) {
    private val prefs: SharedPreferences = runCatching {
        EncryptedSharedPreferences.create(
            context, "auth_session",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        // Corrupted keystore entry (e.g. restored backup): start clean rather than crash.
        context.deleteSharedPreferences("auth_session")
        EncryptedSharedPreferences.create(
            context, "auth_session",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): Session? = prefs.getString("session", null)?.let { runCatching { json.decodeFromString(Session.serializer(), it) }.getOrNull() }
    fun save(s: Session?) = prefs.edit().apply {
        if (s == null) remove("session") else putString("session", json.encodeToString(Session.serializer(), s))
    }.apply()

    fun loadProfileJson(): String? = prefs.getString("profile", null)
    fun saveProfileJson(v: String?) = prefs.edit().apply { if (v == null) remove("profile") else putString("profile", v) }.apply()
}

/**
 * Minimal Supabase client over plain HTTPS (Auth + PostgREST). No SDK: fewer dependencies,
 * one OkHttp client, tokens refreshed lazily only when they are about to expire.
 */
class Supabase(private val store: SessionStore, private val json: Json) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json".toMediaType()
    private val refreshLock = Mutex()

    private val _session = MutableStateFlow(store.load())
    val session: StateFlow<Session?> = _session

    private fun setSession(s: Session?) { store.save(s); _session.value = s }

    // ───────────── low level
    private suspend fun call(method: String, path: String, body: String?, token: String?, prefer: String? = null): String =
        withContext(Dispatchers.IO) {
            if (!SupabaseConfig.configured) throw SupabaseException(0, "التطبيق لسه مش متوصل بقاعدة البيانات")
            val req = Request.Builder().url(SupabaseConfig.url + path)
                .header("apikey", SupabaseConfig.anonKey)
                .apply { if (token != null) header("Authorization", "Bearer $token") }
                .apply { if (prefer != null) header("Prefer", prefer) }
                .method(method, body?.toRequestBody(jsonType))
                .build()
            try {
                http.newCall(req).execute().use { r ->
                    val text = r.body?.string().orEmpty()
                    if (!r.isSuccessful) throw SupabaseException(r.code, errorMessage(text, r.code))
                    text
                }
            } catch (e: java.io.IOException) {
                throw SupabaseException(0, "مفيش اتصال بالإنترنت")
            }
        }

    private fun errorMessage(body: String, code: Int): String {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val raw = listOf("msg", "error_description", "message", "error").firstNotNullOfOrNull { o?.get(it)?.jsonPrimitive?.content }.orEmpty()
        return when {
            raw.contains("Invalid login credentials", true) -> "الإيميل أو كلمة السر غلط"
            raw.contains("expired", true) || raw.contains("invalid", true) && raw.contains("otp", true) -> "الكود غلط أو انتهى — اطلب كود جديد"
            raw.contains("already registered", true) || raw.contains("already exists", true) -> "الإيميل ده متسجل قبل كده — سجّل دخول"
            raw.contains("Email not confirmed", true) -> "أكد إيميلك الأول من الرسالة اللي وصلتك"
            raw.contains("Password should", true) || raw.contains("weak", true) -> "كلمة السر ضعيفة — 6 حروف على الأقل"
            raw.contains("rate limit", true) || code == 429 -> "محاولات كتير — استنى دقيقة وجرّب تاني"
            raw.contains("valid email", true) || raw.contains("invalid format", true) -> "اكتب إيميل صحيح"
            code == 401 || code == 403 -> "انتهت الجلسة — سجّل دخول تاني"
            raw.isNotBlank() -> raw
            else -> "خطأ من الخادم ($code)"
        }
    }

    private fun parseSession(text: String): Session? {
        val o = json.parseToJsonElement(text).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.content ?: return null
        val user = o["user"]?.jsonObject ?: return null
        val expiresAt = o["expires_at"]?.jsonPrimitive?.longOrNull
            ?: (System.currentTimeMillis() / 1000 + (o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600))
        return Session(access, o["refresh_token"]!!.jsonPrimitive.content, expiresAt,
            user["id"]!!.jsonPrimitive.content, user["email"]?.jsonPrimitive?.content)
    }

    // ───────────── auth
    /** @return true if signed in immediately, false if the project requires e-mail confirmation first. */
    suspend fun signUp(email: String, password: String, fullName: String, phone: String): Boolean {
        val body = buildJsonObject {
            put("email", email); put("password", password)
            putJsonObject("data") { put("full_name", fullName); put("phone", phone) }
        }
        val s = parseSession(call("POST", "/auth/v1/signup", body.toString(), null))
        if (s != null) setSession(s)
        return s != null
    }

    suspend fun signIn(email: String, password: String) {
        val body = buildJsonObject { put("email", email); put("password", password) }
        setSession(parseSession(call("POST", "/auth/v1/token?grant_type=password", body.toString(), null))
            ?: throw SupabaseException(0, "رد غير متوقع من الخادم"))
    }

    /** E-mails a one-time login link (and code, if the template has one) that opens the app at [redirect]. */
    suspend fun sendOtp(email: String, redirect: String = APP_REDIRECT) {
        call("POST", "/auth/v1/otp?redirect_to=" + java.net.URLEncoder.encode(redirect, "UTF-8"),
            buildJsonObject { put("email", email); put("create_user", true) }.toString(), null)
    }

    /** Completes a login-link redirect: `dealsmasr://login#access_token=…&refresh_token=…`. */
    suspend fun sessionFromRedirect(fragment: String) {
        val p = fragment.removePrefix("#").split("&").mapNotNull {
            it.split("=", limit = 2).takeIf { kv -> kv.size == 2 }?.let { kv -> kv[0] to java.net.URLDecoder.decode(kv[1], "UTF-8") }
        }.toMap()
        p["error_description"]?.let { throw SupabaseException(400, if (p["error_code"] == "otp_expired") "اللينك انتهى — اطلب لينك جديد" else it) }
        val access = p["access_token"] ?: throw SupabaseException(400, "لينك الدخول ناقص")
        val refresh = p["refresh_token"] ?: throw SupabaseException(400, "لينك الدخول ناقص")
        val user = json.parseToJsonElement(call("GET", "/auth/v1/user", null, access)).jsonObject
        val expiresAt = p["expires_at"]?.toLongOrNull()
            ?: (System.currentTimeMillis() / 1000 + (p["expires_in"]?.toLongOrNull() ?: 3600))
        setSession(Session(access, refresh, expiresAt, user["id"]!!.jsonPrimitive.content, user["email"]?.jsonPrimitive?.content))
    }

    suspend fun verifyOtp(email: String, code: String) {
        val body = buildJsonObject { put("type", "email"); put("email", email); put("token", code) }
        setSession(parseSession(call("POST", "/auth/v1/verify", body.toString(), null))
            ?: throw SupabaseException(0, "الكود غلط أو انتهى"))
    }

    suspend fun rpc(fn: String): String = call("POST", "/rest/v1/rpc/$fn", "{}", token())

    suspend fun resetPassword(email: String) {
        call("POST", "/auth/v1/recover", buildJsonObject { put("email", email) }.toString(), null)
    }

    suspend fun signOut() {
        val token = _session.value?.accessToken
        setSession(null); store.saveProfileJson(null)
        if (token != null) runCatching { call("POST", "/auth/v1/logout", null, token) }
    }

    /** Valid access token, refreshing it (once, shared between callers) when it expires within a minute. */
    suspend fun token(): String = refreshLock.withLock {
        val s = _session.value ?: throw SupabaseException(401, "سجّل دخول الأول")
        if (s.expiresAt - System.currentTimeMillis() / 1000 > 60) return s.accessToken
        val body = buildJsonObject { put("refresh_token", s.refreshToken) }
        try {
            val fresh = parseSession(call("POST", "/auth/v1/token?grant_type=refresh_token", body.toString(), null))
                ?: throw SupabaseException(401, "انتهت الجلسة — سجّل دخول تاني")
            setSession(fresh); fresh.accessToken
        } catch (e: SupabaseException) {
            if (e.status in 400..499) setSession(null) // refresh token revoked/expired → signed out
            throw e
        }
    }

    // ───────────── PostgREST
    suspend fun select(table: String, query: String): String = call("GET", "/rest/v1/$table?$query", null, token())

    suspend fun insert(table: String, row: JsonObject): String =
        call("POST", "/rest/v1/$table", row.toString(), token(), prefer = "return=representation")

    suspend fun update(table: String, filter: String, patch: JsonObject): String =
        call("PATCH", "/rest/v1/$table?$filter", patch.toString(), token(), prefer = "return=representation")
}
