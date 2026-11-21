package com.dealsmasr.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dealsmasr.app.MainActivity
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.data.DealEvent

/**
 * Deal notifications graded by importance:
 *  - 10–29 %  "خصم"          quiet, collected in one summary notification
 *  - 30–49 %  "عرض ساخن 🔥"  normal notification
 *  - 50–69 %  "سوبر عرض ⚡"  heads-up
 *  - 70 %+    "الحق! 🚨"      heads-up, strong vibration, shown on the lock screen
 * A price drop on a deal we already knew is labelled "نزل تاني".
 */
object DealAlerts {
    enum class Level(val channel: String, val nameAr: String, val importance: Int, val minPct: Int) {
        LOW("deal_low", "خصومات عادية (10–29%)", NotificationManager.IMPORTANCE_LOW, 10),
        HOT("deal_hot", "عروض ساخنة (30–49%)", NotificationManager.IMPORTANCE_DEFAULT, 30),
        SUPER("deal_super", "سوبر عروض (50–69%)", NotificationManager.IMPORTANCE_HIGH, 50),
        URGENT("deal_urgent", "الحق! (70% وأكتر)", NotificationManager.IMPORTANCE_HIGH, 70),
    }

    private const val SUMMARY_ID = 7001
    private const val MAX_PER_HOUR = 30
    private val recentLow = ArrayDeque<String>()
    private var lowCount = 0
    private val sentTimes = ArrayDeque<Long>()
    private val sentKeys = LinkedHashMap<String, Long>()

    fun level(pct: Double): Level = when {
        pct >= 70 -> Level.URGENT
        pct >= 50 -> Level.SUPER
        pct >= 30 -> Level.HOT
        else -> Level.LOW
    }

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        Level.entries.forEach { l ->
            nm.createNotificationChannel(NotificationChannel(l.channel, l.nameAr, l.importance).apply {
                if (l == Level.URGENT) {
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 150, 400, 150, 600)
                    lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                }
            })
        }
    }

    private fun pending(ctx: Context, id: Int, route: String?): PendingIntent {
        val intent = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(Notifier.EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(ctx, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** @param minPct user's minimum discount to be notified about. */
    @Synchronized
    fun notify(ctx: Context, events: List<DealEvent>, minPct: Int) {
        if (!Notifier.canNotify(ctx) || events.isEmpty()) return
        val now = System.currentTimeMillis()
        while (sentTimes.isNotEmpty() && now - sentTimes.first() > 3_600_000) sentTimes.removeFirst()
        sentKeys.entries.removeAll { now - it.value > 6 * 3_600_000 }

        for (e in events.sortedByDescending { it.deal.discountPct }) {
            val d = e.deal
            if (d.discountPct < minPct) continue
            val key = "${d.id}@${d.currentPrice}"
            if (key in sentKeys) continue // same product at same price already announced
            sentKeys[key] = now
            val lvl = level(d.discountPct)
            val pct = d.discountPct.toInt()
            val again = if (e.kind == DealEvent.Kind.PRICE_DROP) "نزل تاني! " else ""
            if (lvl == Level.LOW) {
                lowCount++
                recentLow.addFirst("-$pct% ${d.title.take(40)} — ${Money.egp(d.currentPrice)}")
                while (recentLow.size > 6) recentLow.removeLast()
                continue
            }
            if (sentTimes.size >= MAX_PER_HOUR && lvl != Level.URGENT) continue
            sentTimes.addLast(now)
            val title = when (lvl) {
                Level.URGENT -> "🚨 ${again}الحق! خصم $pct% على ${d.marketplaceName}"
                Level.SUPER -> "⚡ ${again}سوبر عرض -$pct% — قبل ما يخلص"
                else -> "🔥 ${again}عرض ساخن -$pct% على ${d.marketplaceName}"
            }
            val body = "${d.title}\n${Money.egp(d.currentPrice)} بدل ${Money.egp(d.originalPrice)} (وفّر ${Money.egp(d.savings)})"
            val id = d.id.hashCode()
            val n = NotificationCompat.Builder(ctx, lvl.channel)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(title)
                .setContentText(body.substringBefore('\n'))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pending(ctx, id, "product/${d.id}"))
                .setPriority(if (lvl == Level.URGENT || lvl == Level.SUPER) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(if (lvl == Level.URGENT) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_RECOMMENDATION)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(id, n) }
        }

        if (lowCount > 0 && Level.LOW.minPct >= minPct) {
            val style = NotificationCompat.InboxStyle()
            recentLow.forEach { style.addLine(it) }
            val n = NotificationCompat.Builder(ctx, Level.LOW.channel)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle("خصومات جديدة: $lowCount")
                .setContentText(recentLow.firstOrNull())
                .setStyle(style)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending(ctx, SUMMARY_ID, "deals"))
                .setAutoCancel(true)
                .setDeleteIntent(null)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(SUMMARY_ID, n) }
        }
    }

    /** Called when the user opens the app: restart the quiet summary counter. */
    @Synchronized
    fun resetSummary() { lowCount = 0; recentLow.clear() }
}
