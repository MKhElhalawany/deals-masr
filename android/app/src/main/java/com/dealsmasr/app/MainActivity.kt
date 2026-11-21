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
import com.dealsmasr.app.work.LiveScanService
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
        lifecycleScope.launch {
            val s = container.settings.current()
            if (s.liveScan && !s.hasServer) LiveScanService.start(this@MainActivity)
        }
        // While the app is visible: refresh deals every 10 minutes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                var tick = 0
                while (true) {
                    if (container.auth.isSignedIn) {
                        runCatching { container.deals.refreshIfDue(10 * 60_000L) }
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
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
            sharedText = text
            external = ExternalNav(sharedText = text)
            return
        }
        intent.getStringExtra(Notifier.EXTRA_ROUTE)?.let { external = ExternalNav(route = it) }
    }
}
