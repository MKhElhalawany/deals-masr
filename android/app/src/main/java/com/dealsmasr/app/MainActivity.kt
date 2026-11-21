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
import com.dealsmasr.app.work.Notifier

class MainActivity : ComponentActivity() {
    private var external by mutableStateOf(ExternalNav())
    private var sharedText by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        val container = (application as DealsApp).container
        // While the app is visible: refresh deals every 10 minutes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { container.deals.refreshIfDue(10 * 60_000L) }
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
            DealsTheme(settings?.theme ?: ThemeMode.SYSTEM) {
                AppNav(
                    external = external,
                    onExternalConsumed = { external = ExternalNav() },
                    sharedText = sharedText,
                    onSharedConsumed = { sharedText = null },
                )
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
