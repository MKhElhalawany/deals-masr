package com.dealsmasr.app.push

import android.content.Context
import android.util.Log
import com.dealsmasr.app.BuildConfig
import com.dealsmasr.app.DealsApp
import com.dealsmasr.app.data.auth.Supabase
import com.dealsmasr.app.work.DealAlerts
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * FCM push from our 24/7 server. Firebase is initialised manually from BuildConfig (public client identifiers
 * injected by CI), so no google-services.json is committed. If the Firebase project isn't set up yet, push is
 * simply off and the app keeps working with Realtime (open) + periodic sync (closed).
 *
 * Topics: the user subscribes to exactly one of deals_min_10/30/50/70 (their chosen minimum discount);
 * the server targets deals with a topic condition, so each phone only gets deals at or above its threshold.
 */
object Push {
    private const val TAG = "Push"
    private val THRESHOLDS = listOf(10, 30, 50, 70)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val configured: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotBlank() && BuildConfig.FIREBASE_API_KEY.isNotBlank() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() && BuildConfig.FIREBASE_SENDER_ID.isNotBlank()

    fun init(ctx: Context): Boolean {
        if (!configured) return false
        return runCatching {
            if (FirebaseApp.getApps(ctx).isEmpty()) {
                FirebaseApp.initializeApp(ctx, FirebaseOptions.Builder()
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build())
            }
            true
        }.onFailure { Log.w(TAG, "firebase init failed: ${it.message}") }.getOrDefault(false)
    }

    private suspend fun fcmToken(): String? = suspendCancellableCoroutine { c ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { t -> c.resume(if (t.isSuccessful) t.result else null) }
    }

    private suspend fun topic(name: String, subscribe: Boolean) = suspendCancellableCoroutine { c ->
        val task = if (subscribe) FirebaseMessaging.getInstance().subscribeToTopic(name) else FirebaseMessaging.getInstance().unsubscribeFromTopic(name)
        task.addOnCompleteListener { c.resume(it.isSuccessful) }
    }

    /** Call after login / when the notification settings change. Safe to call often. */
    fun sync(ctx: Context) {
        if (!init(ctx)) return
        val c = (ctx.applicationContext as DealsApp).container
        io.launch {
            runCatching {
                val s = c.settings.current()
                if (!c.auth.isSignedIn) return@launch
                FirebaseMessaging.getInstance().isAutoInitEnabled = true
                val wanted = if (s.notificationsEnabled) THRESHOLDS.lastOrNull { it <= s.notifyMinPct.coerceAtLeast(10) } ?: 10 else null
                THRESHOLDS.forEach { t -> topic("deals_min_$t", t == wanted) }
                val token = fcmToken() ?: return@launch
                register(c.supabase, token, s.notifyMinPct, s.notificationsEnabled)
            }.onFailure { Log.w(TAG, "sync failed: ${it.message}") }
        }
    }

    suspend fun register(supabase: Supabase, token: String, minPct: Int, enabled: Boolean) {
        supabase.upsert("device_tokens", buildJsonObject {
            put("token", token); put("min_pct", minPct); put("enabled", enabled)
            put("updated_at", java.time.Instant.now().toString())
        })
    }

    /** On sign-out: stop personal pushes to this phone and leave the deal topics. */
    suspend fun unregister(ctx: Context, supabase: Supabase) {
        if (!init(ctx)) return
        runCatching {
            fcmToken()?.let { supabase.delete("device_tokens", "token=eq.$it") }
            THRESHOLDS.forEach { topic("deals_min_$it", false) }
        }
    }
}

class PushService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val c = (applicationContext as DealsApp).container
        if (!c.auth.isSignedIn) return
        scope.launch {
            runCatching {
                val s = c.settings.current()
                Push.register(c.supabase, token, s.notifyMinPct, s.notificationsEnabled)
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        if (data.isEmpty() || data["type"] == "ci") return
        val c = (applicationContext as DealsApp).container
        val s = runCatching { kotlinx.coroutines.runBlocking { c.settings.current() } }.getOrNull() ?: return
        if (!s.notificationsEnabled) return
        // app open: Realtime already delivered personal items (orders / alerts) in-app
        if (data["type"] == "personal" && c.realtime.connected.value) return
        DealAlerts.notifyPush(applicationContext, data, if (data["type"] == "personal") 0 else s.notifyMinPct)
        // keep the local cache fresh too, so the deal is there instantly when the notification is tapped
        if (data["type"] == "deal") scope.launch { runCatching { c.deals.refresh() } }
    }
}
