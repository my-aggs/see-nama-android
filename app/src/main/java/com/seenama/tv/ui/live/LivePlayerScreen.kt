package com.seenama.tv.ui.live

import androidx.annotation.OptIn
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.data.LiveChannel
import com.seenama.tv.player.Playback
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.LoadingDots
import com.seenama.tv.ui.components.SizedImage
import com.seenama.tv.ui.components.cardBorder
import com.seenama.tv.ui.components.cardColors
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.delay

/**
 * Full-screen live channel. Up/Down zaps to the previous/next channel (like a TV remote),
 * OK opens the channel strip, Back closes the strip or leaves. Each channel can have several
 * stream addresses; the next one is tried automatically when one fails.
 */
@OptIn(UnstableApi::class)
@Composable
fun LivePlayerScreen(channelId: Long, nav: Nav) {
    val app = LocalContext.current.app
    var channels by remember { mutableStateOf<List<LiveChannel>>(emptyList()) }
    var index by remember { mutableStateOf(-1) }
    var buffering by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf(0) } // bumps to show the channel banner
    var stripOpen by remember { mutableStateOf(false) }
    var retry by remember { mutableStateOf(0) }
    val rootFocus = remember { FocusRequester() }

    val player = remember {
        app.warmer.release()
        app.warmer.playerActive = true
        Playback.createPlayer(app).apply { playWhenReady = true }
    }
    // Stream candidates of the current channel, and which one is playing.
    var candidates by remember { mutableStateOf<List<String>>(emptyList()) }
    var candidate by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) failed = false
            }
            override fun onPlayerError(error: PlaybackException) {
                // Live edge fell behind: jump back to it.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition(); player.prepare(); return
                }
                if (candidate + 1 < candidates.size) candidate++ else failed = true
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            app.warmer.playerActive = false
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.pause() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { if (index >= 0) player.play() }

    LaunchedEffect(Unit) {
        channels = runCatching { app.farsi.liveChannels() }.getOrDefault(emptyList())
        index = channels.indexOfFirst { it.id == channelId }.coerceAtLeast(0)
        if (channels.isEmpty()) failed = true
    }
    // Zapping: wait a beat so pressing Up three times only tunes the last channel.
    LaunchedEffect(index, retry) {
        val c = channels.getOrNull(index) ?: return@LaunchedEffect
        failed = false
        buffering = true
        banner++
        player.stop()
        player.clearMediaItems() // don't leave the previous channel's last frame on screen
        delay(350)
        candidates = runCatching { app.farsi.liveStreams(c) }.getOrDefault(emptyList())
        candidate = 0
        if (candidates.isEmpty()) failed = true
    }
    LaunchedEffect(candidates, candidate) {
        val url = candidates.getOrNull(candidate) ?: return@LaunchedEffect
        val c = channels.getOrNull(index)
        player.setMediaItem(
            MediaItem.Builder().setUri(url).setMimeType(Playback.mimeType(url))
                .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8_000).build())
                .setMediaMetadata(MediaMetadata.Builder().setTitle(c?.name).build()).build()
        )
        player.prepare()
        player.play()
    }
    var showBanner by remember { mutableStateOf(true) }
    LaunchedEffect(banner) { showBanner = true; delay(4_000); showBanner = false }
    LaunchedEffect(stripOpen) { if (!stripOpen) runCatching { rootFocus.requestFocus() } }

    fun zap(delta: Int) {
        if (channels.isEmpty()) return
        index = (index + delta + channels.size) % channels.size
    }

    BackHandler { if (stripOpen) stripOpen = false else nav.back() }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { ev ->
                if (stripOpen || ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionUp, Key.ChannelUp -> { zap(-1); true }
                    Key.DirectionDown, Key.ChannelDown -> { zap(1); true }
                    Key.DirectionCenter, Key.Enter, Key.DirectionLeft, Key.DirectionRight, Key.Menu -> { stripOpen = true; true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        val current = channels.getOrNull(index)
        if (failed) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${current?.name ?: "This channel"} isn't available right now", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Text("Press Up/Down for another channel", style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary, modifier = Modifier.padding(top = 8.dp))
                if (!stripOpen) Row(Modifier.padding(top = 20.dp)) { ActionButton("Try again", null, { retry++ }) }
            }
        } else if (buffering) {
            LoadingDots(Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(showBanner && current != null && !stripOpen, Modifier.align(Alignment.TopStart), enter = fadeIn(tween(200)), exit = fadeOut(tween(300))) {
            if (current != null) ChannelBanner(current, index, channels.size)
        }
        AnimatedVisibility(stripOpen, Modifier.align(Alignment.BottomStart), enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            ChannelStrip(channels, index) { i -> index = i; stripOpen = false }
        }
    }
    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
}

@Composable
private fun ChannelBanner(c: LiveChannel, index: Int, count: Int) {
    Row(
        Modifier.padding(32.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xE6141414)).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${index + 1}", style = MaterialTheme.typography.headlineMedium, color = Palette.TextSecondary)
        Spacer(Modifier.width(16.dp))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Red))
                Spacer(Modifier.width(6.dp))
                Text("LIVE", style = MaterialTheme.typography.labelMedium, color = Palette.Red)
            }
            Text(c.name, style = MaterialTheme.typography.headlineSmall, color = Color.White)
            Text("▲ ▼ change channel  ·  OK all channels  ·  $count channels", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun ChannelStrip(channels: List<LiveChannel>, current: Int, onPick: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current.coerceAtLeast(0))
    val currentFocus = remember { FocusRequester() }
    Column(
        Modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Black.copy(alpha = 0.85f)))
            .padding(top = 40.dp, bottom = 32.dp),
    ) {
        Text("Channels", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        LazyRow(state = state, contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
                Column(Modifier.width(200.dp)) {
                    Card(
                        onClick = { onPick(i) },
                        modifier = Modifier.fillMaxWidth().size(200.dp, 112.dp).then(if (i == current) Modifier.focusRequester(currentFocus) else Modifier),
                        shape = CardDefaults.shape(RoundedCornerShape(6.dp)),
                        scale = CardDefaults.scale(focusedScale = 1.06f),
                        border = cardBorder(),
                        colors = cardColors(),
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            SizedImage(c.logo, 200.dp, 112.dp, Modifier.fillMaxSize())
                            if (i == current) {
                                Text("NOW", style = MaterialTheme.typography.labelSmall, color = Color.White,
                                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Red).padding(horizontal = 5.dp))
                            }
                        }
                    }
                    Text(c.name, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        repeat(20) { if (runCatching { currentFocus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(30) }
    }
}
