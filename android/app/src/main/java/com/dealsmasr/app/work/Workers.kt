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
            .setSmallIcon(android.R.drawable.stat_notify_more)
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

/** Periodic: refresh deal snapshot, re-verify favourites, run user alerts, fetch major-deal events. */
class DealRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as DealsApp).container
        val s = c.settings.current()
        if (!s.hasServer) return Result.success()
        return try {
            c.deals.refresh()
            if (s.notificationsEnabled) {
                c.deals.refreshFavorites().take(3).forEach { d ->
                    Notifier.show(applicationContext, d.favorite.productId.hashCode(), Notifier.CH_DEALS,
                        "نزل السعر! ${Money.egp(d.newPrice)}", d.favorite.title, "product/${d.favorite.productId}")
                }
                c.deals.checkAlerts().forEach { (alert, items) ->
                    val best = items.maxBy { it.discountPct }
                    Notifier.show(applicationContext, ("alert" + alert.id).hashCode(), Notifier.CH_DEALS,
                        "تنبيه: ${alert.query} بخصم ${best.discountPct.toInt()}%",
                        "${best.title} — ${Money.egp(best.currentPrice)} (${items.size} عرض جديد)", "product/${best.id}")
                }
                if (s.majorDealAlerts) {
                    c.deals.majorDealNotifications().take(3).forEach { n ->
                        Notifier.show(applicationContext, ("n" + n.id).hashCode(), Notifier.CH_DEALS, n.title, n.body,
                            n.productId?.let { "product/$it" })
                    }
                }
            } else {
                c.deals.refreshFavorites()
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}

/** Daily: sync order statuses and fire countdown milestone notifications. */
class OrderMilestoneWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as DealsApp).container
        runCatching { c.shein.syncOrders() }
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
            "deal_refresh", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DealRefreshWorker>(6, TimeUnit.HOURS).setConstraints(net).build(),
        )
        wm.enqueueUniquePeriodicWork(
            "order_milestones", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<OrderMilestoneWorker>(12, TimeUnit.HOURS).build(),
        )
    }
}
