package com.seenama.tv.ui.login

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.LoadingDots
import com.seenama.tv.ui.components.Wordmark
import com.seenama.tv.ui.components.qrBitmap
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun LoginScreen(nav: Nav) {
    val app = LocalContext.current.app
    val repo = app.repo
    var code by remember { mutableStateOf<Pair<String, String>?>(null) }
    var qr by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) } // bump to restart polling / fetch a new code
    var polling by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        runCatching { repo.qrCode() }
            .onSuccess { c -> code = c; qr = withContext(Dispatchers.Default) { qrBitmap(c.second, 560) } }
            .onFailure { error = true }
    }
    // Poll every 5s for 60s, like the official app; "check again" restarts the window.
    LaunchedEffect(code, round) {
        val c = code?.first?.takeIf { it.isNotEmpty() } ?: return@LaunchedEffect
        polling = true
        val until = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < until) {
            if (runCatching { repo.qrPoll(c) }.getOrDefault(false)) { nav.toMain(); return@LaunchedEffect }
            delay(5_000)
        }
        polling = false
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(horizontal = 80.dp, vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Wordmark(36)
            Spacer(Modifier.height(4.dp))
            Text("Sign in with your phone", style = MaterialTheme.typography.headlineLarge, color = Color.White)
            Step(1, "Scan the QR code with your phone, or open this address in a browser:")
            Text(
                code?.second?.ifEmpty { null } ?: "…",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh).padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Step(2, "Sign in to your 30nama account and approve the login.")
            Step(3, "This screen updates automatically.")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton(
                    if (polling) "Checking…" else "Check again",
                    Icons.Default.Refresh,
                    { round++ },
                    Modifier.focusRequester(focus),
                )
                ActionButton("Continue as guest", Icons.AutoMirrored.Filled.Login, { app.prefs.guest = true; nav.toMain() })
            }
            if (error) Text("Couldn't get a login code.", color = Palette.Red, style = MaterialTheme.typography.bodyLarge)
        }
        Box(
            Modifier.size(300.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            val bmp = qr
            if (bmp != null) Image(bmp.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
            else LoadingDots()
        }
        Spacer(Modifier.width(24.dp))
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(50)).background(Palette.Red), contentAlignment = Alignment.Center) {
            Text(n.toString(), color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
    }
}
