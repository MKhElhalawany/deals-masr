package com.dealsmasr.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.dealsmasr.app.AppContainer
import com.dealsmasr.app.DealsApp
import com.dealsmasr.app.MainActivity
import com.dealsmasr.app.R
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.core.OrderCountdown
import java.util.concurrent.TimeUnit

/**
 * Local notifications. Push (FCM) can be added later by forwarding the message
 * payload to [Notifier.show]; the backend already queues "major deal" events.
 */
object Notifier {
    const val CH_DEALS = "deals"
    const val CH_ORDERS = "orders"
    const val EXTRA_ROUTE = "route"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_DEALS, ctx.getString(R.string.channel_deals), NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_ORDERS, ctx.getString(R.string.channel_orders), NotificationManager.IMPORTANCE_DEFAULT))
        DealAlerts.createChannels(ctx)
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun show(ctx: Context, id: Int, channel: String, title: String, body: String, route: String? = null) {
        if (!canNotify(ctx)) return
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(EXTRA_ROUTE, it) }
        }
        val pi = PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(com.dealsmasr.app.R.drawable.ic_stat_deal)
                .setColor(0xFFC9971C.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(id, n)
        } catch (_: SecurityException) {
        }
    }
}

/** Periodic (every 15 min): fetch what the server found, re-verify favourites, run user alerts, fetch major-deal events. */
class DealRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as DealsApp).container
        if (!c.auth.isSignedIn) return Result.success()
        val s = c.settings.current()
        return try {
            // Only READS from our server (Supabase); all store scraping happens on the 24/7 server.
            val events = c.deals.refresh()
            if (s.notificationsEnabled) {
                DealAlerts.notify(applicationContext, events, s.notifyMinPct)
                c.deals.serverNotifications().forEach { n ->
                    Notifier.show(applicationContext, (n.dealId ?: ("sn" + n.id)).hashCode(), Notifier.CH_DEALS, n.title, n.body,
                        n.dealId?.let { "product/$it" })
                }
            }
            if (s.notificationsEnabled && s.crazyNotify) runCatching { crazyDeals(c) }
            runCatching { OrderEvents.check(applicationContext, c) }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** New posts in "عروض مطرقعة" since the last check → one notification each (max 3), newest first. */
private suspend fun CoroutineWorker.crazyDeals(c: AppContainer) {
    val seen = c.settings.crazySeen().let { if (it < 1_000_000_000L) 0L else it }   // older versions stored a post number
    val posts = c.deals.crazyPosts()
    val top = posts.firstOrNull()?.postedAt ?: return
    if (seen == 0L) { c.settings.setCrazySeen(top); return }   // first run: don't flood with old posts
    posts.filter { it.postedAt > seen }.take(3).forEach { p ->
        Notifier.show(applicationContext, ("tg" + p.key).hashCode(), Notifier.CH_DEALS, "🔥 عرض مطرقع جديد",
            p.text.lineSequence().firstOrNull { it.isNotBlank() }?.take(120) ?: "عرض جديد", "crazy?f=0")
    }
    c.settings.setCrazySeen(top)
}

/** Daily: sync order statuses and fire countdown milestone notifications. */
class OrderMilestoneWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as DealsApp).container
        runCatching { OrderEvents.check(applicationContext, c) }
        val s = c.settings.current()
        val now = System.currentTimeMillis()
        for (o in c.shein.allOrders()) {
            val confirmed = o.confirmedAt ?: continue
            if (o.status in setOf("delivered", "cancelled")) continue
            val left = OrderCountdown.daysRemaining(confirmed, now, o.countdownDays)
            val notified = o.notifiedMilestones.split(",").mapNotNull { it.toIntOrNull() }.toSet()
            val due = OrderCountdown.dueMilestones(left, notified)
            if (due.isEmpty()) continue
            if (s.notificationsEnabled) {
                Notifier.show(applicationContext, ("o" + o.id).hashCode(), Notifier.CH_ORDERS,
                    "طلب #${o.id}", OrderCountdown.remainingText(left), "order/${o.id}")
            }
            c.shein.saveOrder(o.copy(notifiedMilestones = (notified + due).joinToString(",")))
        }
        return Result.success()
    }
}

object WorkScheduler {
    fun schedule(ctx: Context) {
        val wm = WorkManager.getInstance(ctx)
        val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        wm.enqueueUniquePeriodicWork(
            "deal_refresh", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<DealRefreshWorker>(15, TimeUnit.MINUTES).setConstraints(net).build(),
        )
        wm.enqueueUniquePeriodicWork(
            "order_milestones", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OrderMilestoneWorker>(12, TimeUnit.HOURS).build(),
        )
    }
}
