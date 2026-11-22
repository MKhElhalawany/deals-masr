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
}

class DealsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifier.createChannels(this)
        WorkScheduler.schedule(this)
    }
}
