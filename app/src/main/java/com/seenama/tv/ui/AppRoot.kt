package com.seenama.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.SignalWifiStatusbarConnectedNoInternet4
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.data.AppNotification
import com.seenama.tv.data.ServerHealth
import com.seenama.tv.ui.components.SizedImage
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** App shell: navigation plus the global overlays (connection banner, notification toast). */
@Composable
fun AppRoot() {
    val app = LocalContext.current.app
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Notifications: refresh on every resume, then poll while in the foreground.
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            launch { runCatching { app.repo.refreshNotifications() } }
            app.repo.pollNotifications()
        }
    }
    // When connectivity or the server comes back, refresh everything quietly in the background.
    LaunchedEffect(Unit) {
        app.connection.state.map { it.health != ServerHealth.Offline }.distinctUntilChanged().drop(1).collect { ok ->
            if (ok) app.repo.refreshAllInBackground()
        }
    }

    val reconnects by app.connection.reconnects.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize()) {
        androidx.compose.runtime.CompositionLocalProvider(com.seenama.tv.ui.components.LocalReconnects provides reconnects) { AppNav() }
        ConnectionBanner(Modifier.align(Alignment.TopCenter))
        NotificationToast(Modifier.align(Alignment.TopEnd))
    }
}

private enum class BannerKind { Offline, ServerDown, Slow, BackOnline }

@Composable
private fun ConnectionBanner(modifier: Modifier) {
    val app = LocalContext.current.app
    val state by app.connection.state.collectAsStateWithLifecycle()
    var banner by remember { mutableStateOf<BannerKind?>(null) }
    var wasDown by remember { mutableStateOf(false) }

    LaunchedEffect(state.health, state.network) {
        when {
            !state.network -> { banner = BannerKind.Offline; wasDown = true }
            state.health == ServerHealth.Offline -> {
                // Give automatic retries/mirror switching a moment before alarming the user.
                delay(2500)
                banner = BannerKind.ServerDown; wasDown = true
            }
            wasDown -> {
                banner = BannerKind.BackOnline; wasDown = false
                delay(3000)
                banner = null
            }
            state.health == ServerHealth.Degraded -> {
                delay(4000)
                banner = BannerKind.Slow
                delay(5000)
                if (banner == BannerKind.Slow) banner = null
            }
            else -> if (banner != BannerKind.BackOnline) banner = null
        }
    }

    AnimatedVisibility(
        banner != null,
        modifier = modifier.padding(top = 20.dp),
        enter = slideInVertically(tween(250)) { -it } + fadeIn(tween(250)),
        exit = slideOutVertically(tween(250)) { -it } + fadeOut(tween(250)),
    ) {
        val kind = banner ?: BannerKind.BackOnline
        val (icon, color, text) = when (kind) {
            BannerKind.Offline -> Triple(Icons.Default.CloudOff, Palette.Red, "No internet connection. We'll reconnect automatically.")
            BannerKind.ServerDown -> Triple(Icons.Default.SignalWifiStatusbarConnectedNoInternet4, Palette.Red, "Can't reach 30nama right now. Trying other servers…")
            BannerKind.Slow -> Triple(Icons.Default.Wifi, Color(0xFFF5A524), "Slow connection to the server")
            BannerKind.BackOnline -> Triple(Icons.Default.Wifi, Palette.Match, "You're back online")
        }
        Row(
            Modifier.clip(RoundedCornerShape(24.dp)).background(Color(0xF2232323)).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
    }
}

@Composable
private fun NotificationToast(modifier: Modifier) {
    val app = LocalContext.current.app
    var current by remember { mutableStateOf<AppNotification?>(null) }
    LaunchedEffect(Unit) {
        app.repo.incoming.collect { n ->
            current = n
            delay(7000)
            if (current == n) current = null
        }
    }
    AnimatedVisibility(
        current != null,
        modifier = modifier.padding(top = 32.dp, end = 40.dp),
        enter = slideInHorizontally(tween(300)) { it } + fadeIn(tween(300)),
        exit = slideOutHorizontally(tween(250)) { it } + fadeOut(tween(250)),
    ) {
        val n = current ?: return@AnimatedVisibility
        Row(
            Modifier.widthIn(max = 460.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xF2232323)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(width = 96.dp, height = 54.dp).clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh)) {
                SizedImage(n.post?.landscape, 96.dp, 54.dp, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NotificationsActive, null, tint = Palette.Red, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("New", style = MaterialTheme.typography.labelMedium, color = Palette.Red)
                }
                Spacer(Modifier.height(2.dp))
                Text(n.headline, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                n.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1) }
            }
        }
    }
}
