package com.seenama.tv.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.ui.theme.Palette

@Composable
fun Wordmark(size: Int = 34, modifier: Modifier = Modifier) {
    Text(
        "NAMA",
        color = Palette.Red,
        fontSize = size.sp,
        fontWeight = FontWeight.Black,
        letterSpacing = (size / 12).sp,
        modifier = modifier,
    )
}

/** Netflix-style action button: white when focused, translucent grey otherwise. */
@Composable
fun ActionButton(
    text: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ButtonDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.16f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
        scale = ButtonDefaults.scale(focusedScale = 1.04f),
        contentPadding = ButtonDefaults.ContentPadding,
    ) {
        if (icon != null) Icon(icon, contentDescription = text.ifEmpty { null }, modifier = Modifier.size(22.dp))
        if (icon != null && text.isNotEmpty()) Spacer(Modifier.width(10.dp))
        if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun LoadingDots(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "dots")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(500, delayMillis = i * 160), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(10.dp).alpha(a).background(Palette.Red, RoundedCornerShape(50)))
        }
    }
}

@Composable
fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots() }
}

/**
 * Full-screen error with a Retry button. When the failure was caused by
 * connectivity, it says so and retries by itself as soon as the connection is back.
 */
@Composable
fun ErrorState(message: String, autoRetry: Boolean = true, onRetry: () -> Unit) {
    val fr = remember { FocusRequester() }
    val connection by androidx.compose.ui.platform.LocalContext.current.let { ctx ->
        (ctx.applicationContext as com.seenama.tv.App).connection.state
    }.collectAsState()
    val offline = connection.health == com.seenama.tv.data.ServerHealth.Offline
    var sawOffline by remember { androidx.compose.runtime.mutableStateOf(offline) }
    LaunchedEffect(offline) {
        if (offline) sawOffline = true
        else if (autoRetry && sawOffline) { sawOffline = false; onRetry() }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(
            if (!connection.network) "No internet connection" else message,
            style = MaterialTheme.typography.titleLarge, color = Palette.TextSecondary,
        )
        if (offline) {
            Spacer(Modifier.size(8.dp))
            Text("We'll try again automatically when you're back online.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        }
        Spacer(Modifier.size(20.dp))
        ActionButton("Try again", null, onRetry, Modifier.focusRequester(fr).padding(4.dp))
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}
