package com.dealsmasr.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.dealsmasr.app.data.ThemeMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.dealsmasr.app.ui.AppNav
import com.dealsmasr.app.ui.ExternalNav
import com.dealsmasr.app.ui.theme.DealsTheme
import com.dealsmasr.app.work.DealAlerts
import com.dealsmasr.app.work.Notifier
import com.dealsmasr.app.work.OrderEvents
import com.dealsmasr.app.data.auth.AuthState
import com.dealsmasr.app.ui.auth.AuthScreen
import com.dealsmasr.app.ui.auth.SplashScreen

class MainActivity : ComponentActivity() {
    private var external by mutableStateOf(ExternalNav())
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        val container = (application as DealsApp).container
        DealAlerts.resetSummary()
        // While the app is visible: pull new deals from our server every minute.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var tick = 0
                while (true) {
                    if (container.auth.isSignedIn) {
                        // read-only sync of what the server found (incremental, tiny)
                        runCatching { container.deals.refresh() }
                        // every 2 minutes: order confirmations (customers) / new orders (admin)
                        if (tick % 2 == 0) runCatching { OrderEvents.check(this@MainActivity, container) }
                    }
                    tick++
                    delay(60_000)
                }
            }
        }
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(null)
            val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this@MainActivity)) {
                    askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            val authState by container.auth.state.collectAsStateWithLifecycle()
            DealsTheme(settings?.theme ?: ThemeMode.SYSTEM) {
                when (authState) {
                    AuthState.Loading -> SplashScreen()
                    AuthState.NotConfigured, AuthState.SignedOut -> AuthScreen(notConfigured = authState == AuthState.NotConfigured)
                    is AuthState.SignedIn -> AppNav(
                        external = external,
                        onExternalConsumed = { external = ExternalNav() },
                        sharedText = sharedText,
                        onSharedConsumed = { sharedText = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        if (data != null && data.scheme == "dealsmasr" && data.host == "login") {
            val fragment = data.encodedFragment ?: data.encodedQuery ?: ""
            val auth = (application as DealsApp).container.auth
            lifecycleScope.launch {
                val msg = runCatching { auth.completeLoginLink(fragment) }
                    .fold({ owner -> if (owner) "أهلاً يا صاحب المتجر — بقيت أدمن ✓" else "تم تسجيل الدخول" }, { it.message ?: "تعذر تسجيل الدخول" })
                android.widget.Toast.makeText(this@MainActivity, msg, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            sharedText = text
            external = ExternalNav(sharedText = text)
            return
        }
        intent.getStringExtra(Notifier.EXTRA_ROUTE)?.let { external = ExternalNav(route = it) }
    }
}
