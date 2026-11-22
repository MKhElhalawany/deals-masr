package com.dealsmasr.app.data.realtime

import android.util.Log
import com.dealsmasr.app.data.auth.Supabase
import com.dealsmasr.app.data.auth.SupabaseConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** One row change pushed by Supabase Realtime (postgres_changes). */
data class RowChange(val table: String, val type: String, val record: JsonObject?, val receivedAt: Long)

/**
 * Supabase Realtime over a single WebSocket (Phoenix protocol, vsn 1.0.0) — the same channel the web SDK uses.
 * Subscribes to changes on deals, the user's notifications, orders and SHEIN quotes. Row Level Security is applied
 * by Supabase, so each user only receives rows they are allowed to read. Runs only while the app is visible;
 * when the app is closed, FCM push takes over.
 */
class RealtimeClient(private val supabase: Supabase, private val json: Json, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val ref = AtomicInteger(1)
    private var socket: WebSocket? = null
    private var loop: Job? = null
    private var lastToken: String? = null
    @Volatile private var wanted = false
    /** Only deal changes at or above this discount are pushed to the phone (smaller ones arrive with the periodic catch-up). */
    @Volatile var minPct: Int = 10
        private set

    /** Changes the deal filter; reconnects only if it actually changed. */
    fun setMinPct(v: Int) {
        val n = v.coerceIn(10, 95)
        if (n == minPct) return
        minPct = n
        if (wanted) { stop(); start() }
    }

    private val _connected = MutableStateFlow(false)
    /** true once the channel is joined and Postgres changes are flowing. */
    val connected: StateFlow<Boolean> = _connected
    private val _changes = MutableSharedFlow<RowChange>(extraBufferCapacity = 256)
    val changes: SharedFlow<RowChange> = _changes
    /** Emits every time a (re)subscription succeeds — callers use it to catch up on anything missed while offline. */
    private val _subscribed = MutableSharedFlow<Unit>(extraBufferCapacity = 4)
    val subscribed: SharedFlow<Unit> = _subscribed

    fun start() {
        if (wanted || !SupabaseConfig.configured) return
        wanted = true
        loop = scope.launch {
            var backoff = 1_000L
            while (isActive && wanted) {
                val ok = runCatching { connectOnce() }.onFailure { Log.w(TAG, "realtime: ${it.message}") }.getOrDefault(false)
                // connectOnce returns when the socket closes; reconnect with capped exponential backoff
                backoff = if (ok) 1_000L else (backoff * 2).coerceAtMost(30_000L)
                if (wanted) delay(backoff)
            }
        }
    }

    fun stop() {
        wanted = false
        loop?.cancel(); loop = null
        socket?.close(1000, "background"); socket = null
        _connected.value = false
    }

    private fun msg(topic: String, event: String, payload: JsonObject) = buildJsonObject {
        put("topic", topic); put("event", event); put("payload", payload); put("ref", ref.getAndIncrement().toString())
    }.toString()

    /** @return true if the channel was joined at least once on this socket. */
    private suspend fun connectOnce(): Boolean {
        val token = supabase.token()
        lastToken = token
        val closed = kotlinx.coroutines.CompletableDeferred<Unit>()
        var joined = false
        val url = SupabaseConfig.url.replace("https://", "wss://") +
            "/realtime/v1/websocket?apikey=${SupabaseConfig.anonKey}&vsn=1.0.0"
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val changes = buildJsonArray {
                    add(buildJsonObject { put("event", "*"); put("schema", "public"); put("table", "deals"); put("filter", "discount_pct=gte.$minPct") })
                    listOf("user_notifications" to "INSERT", "orders" to "*", "shein_quotes" to "UPDATE").forEach { (t, e) ->
                        add(buildJsonObject { put("event", e); put("schema", "public"); put("table", t) })
                    }
                }
                webSocket.send(msg(TOPIC, "phx_join", buildJsonObject {
                    putJsonObject("config") {
                        putJsonObject("broadcast") { put("self", false) }
                        putJsonObject("presence") { put("key", "") }
                        put("postgres_changes", changes)
                    }
                    put("access_token", token)
                }))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val now = System.currentTimeMillis()
                val m = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                val event = m["event"]?.jsonPrimitive?.contentOrNull
                val payload = m["payload"] as? JsonObject ?: return
                when (event) {
                    "system" -> if (payload["status"]?.jsonPrimitive?.contentOrNull == "ok" && !joined) {
                        joined = true; _connected.value = true; _subscribed.tryEmit(Unit)
                    }
                    "phx_reply" -> if (payload["status"]?.jsonPrimitive?.contentOrNull == "error") {
                        Log.w(TAG, "join refused: $payload"); webSocket.close(1000, "join refused")
                    }
                    "postgres_changes" -> {
                        val data = payload["data"] as? JsonObject ?: return
                        val table = data["table"]?.jsonPrimitive?.contentOrNull ?: return
                        val type = data["type"]?.jsonPrimitive?.contentOrNull ?: return
                        _changes.tryEmit(RowChange(table, type, data["record"] as? JsonObject, now))
                    }
                    "phx_error", "phx_close" -> webSocket.close(1000, event)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { closed.complete(Unit) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure: ${t.message}"); closed.complete(Unit)
            }
        }
        val ws = http.newWebSocket(Request.Builder().url(url).build(), listener)
        socket = ws
        // heartbeat every 25 s + keep the JWT fresh on the channel (Supabase drops expired tokens)
        val beat = scope.launch {
            while (isActive) {
                delay(25_000)
                ws.send(msg("phoenix", "heartbeat", buildJsonObject {}))
                val t = runCatching { supabase.token() }.getOrNull()
                if (t != null && t != lastToken) {
                    lastToken = t
                    ws.send(msg(TOPIC, "access_token", buildJsonObject { put("access_token", t) }))
                }
            }
        }
        try {
            closed.await()
        } finally {
            beat.cancel()
            _connected.value = false
            if (socket === ws) socket = null
        }
        return joined
    }

    companion object {
        private const val TAG = "Realtime"
        private const val TOPIC = "realtime:app"
    }
}
