package com.seenama.tv.ui.notifications

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.data.AppNotification
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.LoadingDots
import com.seenama.tv.ui.components.SizedImage
import com.seenama.tv.ui.components.rememberFocusMemory
import com.seenama.tv.ui.components.rememberInitialFocus
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun NotificationsScreen(nav: Nav) {
    val repo = LocalContext.current.app.repo
    val state by repo.notifications.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = rememberInitialFocus()
    val memory = rememberFocusMemory()
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { runCatching { repo.refreshNotifications() }; loading = false }

    Column(Modifier.fillMaxSize().padding(start = 48.dp, top = 40.dp, end = 48.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notifications", style = MaterialTheme.typography.displaySmall, color = Color.White, modifier = Modifier.weight(1f))
            if (state.unread > 0) {
                ActionButton("Mark all as read", Icons.Default.DoneAll, { scope.launch { repo.markAllNotificationsRead() } })
            }
        }
        Spacer(Modifier.height(20.dp))
        when {
            !repo.isLoggedIn -> Empty("Sign in to get notified about new episodes of shows you follow.") {
                ActionButton("Sign in", null, { nav.login() }, Modifier.focusRequester(first))
            }
            state.items.isEmpty() && loading -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { LoadingDots() }
            state.items.isEmpty() -> Empty("You're all caught up. Turn on \"Notify me\" on a show to hear about new episodes.") {}
            else -> LazyColumn(
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.items, key = { it.id }) { n ->
                    val m = memory.modifier("n:${n.id}")
                    NotificationRow(n, if (n == state.items.first()) m.focusRequester(first) else m) {
                        scope.launch { repo.markNotificationRead(n.id) }
                        n.post?.let { nav.details(it.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Empty(text: String, action: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.NotificationsNone, null, tint = Palette.TextMuted, modifier = Modifier.size(56.dp))
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
        Spacer(Modifier.height(20.dp))
        action()
    }
}

@Composable
private fun NotificationRow(n: AppNotification, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (n.read) Color.Transparent else Color.White.copy(alpha = 0.05f),
            contentColor = Color.White,
            focusedContainerColor = Color.White.copy(alpha = 0.16f),
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (n.read) Color.Transparent else Palette.Red))
            Spacer(Modifier.width(14.dp))
            Box(Modifier.size(width = 160.dp, height = 90.dp).clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh)) {
                SizedImage(n.post?.landscape, 160.dp, 90.dp, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f)) {
                Text(n.headline, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                n.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 2) }
                n.dateMs?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString(),
                        style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted,
                    )
                }
            }
        }
    }
}
