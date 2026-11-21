package com.dealsmasr.app.work

import android.content.Context
import com.dealsmasr.app.AppContainer
import com.dealsmasr.app.core.Money
import com.dealsmasr.app.core.OrderCountdown
import java.time.Instant

/**
 * Order notifications without push infrastructure (cheap polling, at most every 2 min in foreground / 15 min in background):
 *  - customer: "تم تأكيد طلبك" when the shop confirms
 *  - admin: "طلب جديد" for every new submitted order
 */
object OrderEvents {
    suspend fun check(ctx: Context, c: AppContainer) {
        val s = c.settings.current()
        c.shein.syncOrders().forEach { o ->
            if (s.notificationsEnabled) Notifier.show(ctx, ("c" + o.id).hashCode(), Notifier.CH_ORDERS,
                "تم تأكيد طلبك 🎉", "طلب #${o.id} اتأكد وهيوصلك خلال ${OrderCountdown.MIN_DAYS}–${o.countdownDays} يوم", "order/${o.id}")
        }
        if (!s.adminMode) return
        val since = c.settings.lastAdminOrderSeen()
        if (since.isBlank()) { c.settings.setLastAdminOrderSeen(Instant.now().toString()); return }
        val fresh = c.admin.submittedSince(since)
        fresh.forEach { o ->
            Notifier.show(ctx, ("n" + o.id).hashCode(), Notifier.CH_ORDERS, "🛍️ طلب جديد من ${o.customerName}",
                "${o.items.size} منتج · ${Money.egp(o.totalEgp)} · ${o.customerPhone}", "admin_orders")
        }
        fresh.lastOrNull()?.let { c.settings.setLastAdminOrderSeen(it.createdAt) }
    }
}
