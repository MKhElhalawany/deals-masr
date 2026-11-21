package com.dealsmasr.app.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dealsmasr.app.DealsApp
import com.dealsmasr.app.MainActivity
import com.dealsmasr.app.data.StoreBlockedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Always-on price monitor. Runs as a foreground service (visible ongoing notification) so it keeps
 * working with the app closed. Reads one store page every [interval] seconds, cycling through stores,
 * keywords, store deal pages and user pages; backs off from a store for 10 minutes when it blocks us.
 */
class LiveScanService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val blockedUntil = HashMap<String, Long>()
    private val fmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            scope.launch { (application as DealsApp).container.settings.setLiveScan(false) }
            stopSelf(); return START_NOT_STICKY
        }
        createChannel(this)
        ServiceCompat.startForeground(
            this, NOTIF_ID, ongoing("بدء المراقبة…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (wakeLock == null) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DealsMasr:live").apply { setReferenceCounted(false) }
        }
        if (loop?.isActive != true) loop = scope.launch { run() }
        return START_STICKY
    }

    private suspend fun run() {
        val c = (application as DealsApp).container
        var i = 0
        var round = 0L
        while (scope.isActive) {
            val s = c.settings.current()
            if (!s.liveScan || s.hasServer) { stopSelf(); return }
            val interval = s.liveIntervalSec.coerceAtLeast(MIN_INTERVAL_SEC) * 1000L
            if (s.liveWifiOnly && isMetered()) {
                update("متوقف مؤقتاً: مستني Wi-Fi"); delay(60_000); continue
            }
            val targets = c.deals.local.liveTargets().filter { (blockedUntil[it.scraper.id] ?: 0) < System.currentTimeMillis() }
            if (targets.isEmpty()) { update("كل المتاجر حاجباك مؤقتاً — هنحاول تاني بعد شوية"); delay(60_000); continue }
            val t = targets[i % targets.size]; i++
            wakeLock?.acquire(90_000)
            try {
                val events = c.deals.local.scan(t)
                DealAlerts.notify(this, events, s.notifyMinPct)
                val found = events.size
                update("آخر فحص ${fmt.format(Instant.now().atZone(ZoneId.of("Africa/Cairo")))} · ${t.label}" +
                    if (found > 0) " · $found جديد" else "")
            } catch (e: StoreBlockedException) {
                blockedUntil[e.store] = System.currentTimeMillis() + 10 * 60_000
                update("${t.label}: المتجر حجب مؤقتاً، هنرجعله بعد 10 دقايق")
            } catch (e: Exception) {
                update("${t.label}: ${e.message ?: "خطأ"}")
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
            }
            if (++round % 200 == 0L) runCatching { c.deals.local.prune() }
            delay(interval)
        }
    }

    private fun isMetered(): Boolean =
        (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered

    private fun update(text: String) {
        if (Notifier.canNotify(this)) runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, ongoing(text)) }
    }

    private fun ongoing(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveScanService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("مراقبة العروض شغالة")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "إيقاف", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        scope.cancel()
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        super.onDestroy()
    }

    companion object {
        const val MIN_INTERVAL_SEC = 10
        private const val NOTIF_ID = 4242
        private const val CHANNEL = "live_scan"
        private const val ACTION_STOP = "stop"

        fun createChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= 26) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "مراقبة العروض (ثابت)", NotificationManager.IMPORTANCE_MIN))
            }
        }

        fun start(ctx: Context) = runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, LiveScanService::class.java))
        }

        fun stop(ctx: Context) = ctx.stopService(Intent(ctx, LiveScanService::class.java))
    }
}

/** Restarts the monitor after a phone reboot if the user left it on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val s = (context.applicationContext as DealsApp).container.settings.current()
                if (s.liveScan && !s.hasServer) LiveScanService.start(context)
            } finally { pending.finish() }
        }
    }
}
