package com.seenama.tv

import android.os.Bundle
import kotlinx.coroutines.launch
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.seenama.tv.ui.AppRoot
import com.seenama.tv.ui.theme.AppTheme
import com.seenama.tv.ui.theme.Palette

class MainActivity : ComponentActivity() {
    /** Debug: `am start -f 0x20000000 -n com.seenama.tv/.MainActivity --ez offline true|false` toggles simulated offline. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG && intent.hasExtra("offline")) app.connection.simulateOffline = intent.getBooleanExtra("offline", false)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("toast", false)) app.repo.debugAnnounceLatest()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // The Compose root paints the background; a window background would just be overdraw.
        window.setBackgroundDrawable(null)
        if (BuildConfig.DEBUG && intent.getBooleanExtra("demo", false)) app.demoMode = true
        // Debug: `--es probe "action" --es params "k=v&k2=v2"` calls an API action once (output via ApiRaw logs).
        if (BuildConfig.DEBUG) intent.getStringExtra("probe")?.let { action ->
            val params = intent.getStringExtra("params").orEmpty().split('&').filter { '=' in it }
                .associate { it.substringBefore('=') to it.substringAfter('=') as Any? }
            app.scope.launch { runCatching { app.api.call(action, params, retries = 0) } }
        }
        setContent {
            AppTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Box(Modifier.fillMaxSize().background(Palette.Background)) { AppRoot() }
                }
            }
        }
    }
}
