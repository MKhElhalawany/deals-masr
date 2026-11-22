package com.dealsmasr.app

import android.app.Application
import android.content.Context
import com.dealsmasr.app.data.DealsRepository
import com.dealsmasr.app.data.SettingsStore
import com.dealsmasr.app.data.SheinRepository
import com.dealsmasr.app.data.local.AppDatabase
import com.dealsmasr.app.data.remote.ApiProvider
import com.dealsmasr.app.work.Notifier
import com.dealsmasr.app.work.WorkScheduler
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual DI: one container per process. */
class AppContainer(context: Context) {
    val api = ApiProvider()
    val db = AppDatabase.build(context)
    val settings = SettingsStore(context, api.json)
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main)
    val sessionStore = com.dealsmasr.app.data.auth.SessionStore(context, api.json)
    val supabase = com.dealsmasr.app.data.auth.Supabase(sessionStore, api.json)
    val auth = com.dealsmasr.app.data.auth.AuthRepository(supabase, sessionStore, settings, api.json, appScope)
    val admin = com.dealsmasr.app.data.auth.AdminRepository(supabase, api.json)
    val deals = DealsRepository(supabase, api.json, settings, db.deals(), db.favorites(), db.alerts())
    val shein = SheinRepository(context, supabase, api, settings, db.cart(), db.orders())
    val realtime = com.dealsmasr.app.data.realtime.RealtimeClient(supabase, api.json, appScope)
}

class DealsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        WorkScheduler.schedule(this)
        // Push (FCM): register this phone + subscribe to the user's discount threshold whenever login/settings change.
        val c = container
        c.auth.beforeSignOut = { com.dealsmasr.app.push.Push.unregister(this, c.supabase) }
        c.appScope.launch {
            combine(c.auth.state, c.settings.settings.map { it.notificationsEnabled to it.notifyMinPct }.distinctUntilChanged()) { a, n -> a to n }
                .collect { (a, _) -> if (a is com.dealsmasr.app.data.auth.AuthState.SignedIn) com.dealsmasr.app.push.Push.sync(this@DealsApp) }
        }
    }
}
