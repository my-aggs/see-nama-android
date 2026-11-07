package com.seenama.tv.player

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seenama.tv.data.Episode
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.ErrorState
import com.seenama.tv.ui.components.LoadingDots
import com.seenama.tv.ui.components.TrackedFocus
import com.seenama.tv.ui.components.trackFocus
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

private enum class Panel { Episodes, AudioSubs, Quality }

@Composable
fun PlayerScreen(postId: Long, free: Boolean, fileId: Long, nav: Nav) {
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(postId, free, fileId))
    val ui by vm.ui.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.player.pause() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when (val s = ui) {
            PlayerUi.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots() }
            is PlayerUi.Error -> ErrorState(s.message) { vm.load() }
            PlayerUi.NotAllowed -> NotAllowed(nav)
            is PlayerUi.External -> {
                val ctx = androidx.compose.ui.platform.LocalContext.current
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Opening in YouTube…", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                }
                LaunchedEffect(s.url) {
                    com.seenama.tv.ui.components.openYouTube(ctx, s.url)
                    nav.back()
                }
            }
            is PlayerUi.Ready -> PlayerContent(s, vm, nav)
        }
    }
}

@Composable
private fun NotAllowed(nav: Nav) {
    val fr = remember { FocusRequester() }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Upgrade your account to watch this title.", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Sign in", null, { nav.login() }, Modifier.focusRequester(fr))
            ActionButton("Back", null, { nav.back() })
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@OptIn(UnstableApi::class)
@Composable
private fun PlayerContent(s: PlayerUi.Ready, vm: PlayerViewModel, nav: Nav) {
    val player = vm.player
    val isPlaying by vm.isPlaying.collectAsStateWithLifecycle()
    val buffering by vm.buffering.collectAsStateWithLifecycle()
    val subs by vm.subtitles.collectAsStateWithLifecycle()
    val subStyle by vm.subtitleStyle.collectAsStateWithLifecycle()
    val reconnecting by vm.reconnecting.collectAsStateWithLifecycle()
    val prefs = androidx.compose.ui.platform.LocalContext.current.let { (it.applicationContext as com.seenama.tv.App).prefs }

    var controls by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var interaction by remember { mutableLongStateOf(0L) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var scrubTick by remember { mutableLongStateOf(0L) }
    var nextDismissedFor by remember { mutableStateOf<Long?>(null) }

    val rootFocus = remember { TrackedFocus() }
    val seekFocus = remember { TrackedFocus() }
    val skipFocus = remember { TrackedFocus() }
    val nextFocus = remember { TrackedFocus() }

    // Position ticker (4 Hz is plenty for a progress bar and subtitle timing at 250ms granularity).
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(if (subs.isEmpty()) 250 else 100)
        }
    }

    val e = s.episode
    val shownPos = scrub ?: position
    val skip: Pair<String, Long>? = when {
        segment(e.recapStartMs, e.recapEndMs)?.contains(position) == true -> "Skip Recap" to e.recapEndMs!!
        segment(e.introStartMs, e.introEndMs)?.contains(position) == true -> "Skip Intro" to e.introEndMs!!
        else -> null
    }
    // Auto-skip (setting): jump past intros/recaps as soon as they start.
    LaunchedEffect(skip?.second) {
        val target = skip?.second ?: return@LaunchedEffect
        if (prefs.autoSkipIntro) { delay(600); player.seekTo(target) }
    }
    val creditsAt = e.creditsMs ?: (duration - 25_000).takeIf { duration > 120_000 }
    val showNext = s.stream.isSeries && vm.hasNext() && creditsAt != null &&
        position >= creditsAt && nextDismissedFor != e.fileId

    fun touch() { interaction = System.currentTimeMillis() }
    fun show() { controls = true; touch() }
    fun togglePlay() { if (player.isPlaying) player.pause() else player.play(); show() }
    fun scrubBy(forward: Boolean, repeat: Int) {
        val dur = duration.takeIf { it > 0 } ?: return
        val base = 10_000L
        // Accelerate while the key is held: 10s, then up to ~2.5% of the runtime per step.
        val step = if (repeat == 0) base else minOf(base * (1 + repeat / 3), maxOf(base, dur / 40))
        val from = scrub ?: position
        scrub = (from + if (forward) step else -step).coerceIn(0, dur)
        scrubTick = System.currentTimeMillis()
        show()
    }

    // Commit the scrub once the user stops pressing.
    LaunchedEffect(scrubTick) {
        val target = scrub ?: return@LaunchedEffect
        delay(650)
        player.seekTo(target)
        position = target
        scrub = null
    }
    // Auto-hide controls.
    LaunchedEffect(controls, interaction, isPlaying, panel, scrub) {
        if (controls && isPlaying && panel == null && scrub == null) {
            delay(4500)
            controls = false
        }
    }
    // Next-episode countdown (Netflix auto-advances).
    var countdown by remember { mutableStateOf(10) }
    LaunchedEffect(showNext, e.fileId) {
        if (!showNext) return@LaunchedEffect
        countdown = 10
        while (countdown > 0) { delay(1000); countdown-- }
        if (prefs.autoplayNext) vm.next()
    }
    // Where focus should live given what's on screen.
    LaunchedEffect(controls, panel, skip != null, showNext) {
        if (panel != null) return@LaunchedEffect
        val target = when {
            controls -> seekFocus
            showNext -> nextFocus
            skip != null -> skipFocus
            else -> rootFocus
        }
        target.request()
    }

    BackHandler {
        when {
            panel != null -> panel = null
            controls -> controls = false
            showNext -> nextDismissedFor = e.fileId
            else -> { vm.reportProgress(); nav.back() }
        }
    }

    Box(
        Modifier.fillMaxSize()
            .onPreviewKeyEvent { ev -> touch(); handleMediaKeys(ev, vm, ::togglePlay) { f -> scrubBy(f, 0) } }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                    subtitleView?.visibility = android.view.View.GONE
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Invisible focus target that owns the remote while controls are hidden.
        Box(
            Modifier.fillMaxSize().trackFocus(rootFocus).focusable().onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (ev.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { togglePlay(); true }
                    Key.DirectionLeft -> { scrubBy(false, ev.nativeKeyEvent.repeatCount); true }
                    Key.DirectionRight -> { scrubBy(true, ev.nativeKeyEvent.repeatCount); true }
                    Key.DirectionUp, Key.DirectionDown -> { show(); true }
                    else -> false
                }
            }
        )

        if (reconnecting) {
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                LoadingDots()
                Spacer(Modifier.height(16.dp))
                Text("Reconnecting…", style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text("Your place is saved. Playback will resume automatically.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
            }
        } else if (buffering && scrub == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingDots() }
        }

        SubtitleOverlay(subs, subStyle, position, Modifier.align(Alignment.BottomCenter).padding(bottom = if (controls) 170.dp else 56.dp))


        // Controls
        AnimatedVisibility(controls && panel == null, enter = fadeIn(tween(180)), exit = fadeOut(tween(250))) {
            Controls(
                s = s, vm = vm, isPlaying = isPlaying, position = shownPos, duration = duration, scrubbing = scrub != null,
                seekFocus = seekFocus,
                onSeekKey = { forward, repeat -> scrubBy(forward, repeat) },
                onToggle = ::togglePlay,
                onPanel = { panel = it },
                onInteract = ::touch,
            )
        }

        // Skip intro / recap: bottom-end, focused when controls are hidden so one OK press skips.
        if (skip != null && panel == null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = if (controls) 150.dp else 64.dp)) {
                PlayerPill(skip.first, Modifier.trackFocus(skipFocus)) { player.seekTo(skip.second); touch() }
            }
        }
        if (showNext && panel == null) {
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 56.dp, bottom = if (controls) 150.dp else 64.dp)) {
                PlayerPill("Next Episode  ($countdown)", Modifier.trackFocus(nextFocus), Icons.Default.SkipNext) { vm.next() }
            }
        }

        // Side panels
        AnimatedVisibility(
            panel != null,
            modifier = Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally(tween(220)) { it / 3 } + fadeIn(tween(220)),
            exit = slideOutHorizontally(tween(180)) { it / 3 } + fadeOut(tween(180)),
        ) {
            when (panel) {
                Panel.Episodes -> EpisodesPanel(s) { ep -> panel = null; controls = false; vm.playEpisode(ep) }
                Panel.AudioSubs -> AudioSubsPanel(s, vm)
                Panel.Quality -> QualityPanel(s) { src, audio -> panel = null; vm.selectSource(src, audio) }
                null -> Unit
            }
        }
    }
}

private fun handleMediaKeys(ev: KeyEvent, vm: PlayerViewModel, toggle: () -> Unit, scrub: (Boolean) -> Unit): Boolean {
    if (ev.type != KeyEventType.KeyDown) return false
    return when (ev.nativeKeyEvent.keyCode) {
        AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_SPACE -> { toggle(); true }
        AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { vm.player.play(); true }
        AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { vm.player.pause(); true }
        AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { scrub(true); true }
        AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { scrub(false); true }
        AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> vm.next()
        AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> vm.previous()
        else -> false
    }
}

@Composable
private fun Controls(
    s: PlayerUi.Ready,
    vm: PlayerViewModel,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    scrubbing: Boolean,
    seekFocus: TrackedFocus,
    onSeekKey: (Boolean, Int) -> Unit,
    onToggle: () -> Unit,
    onPanel: (Panel) -> Unit,
    onInteract: () -> Unit,
) {
    val e = s.episode
    Box(Modifier.fillMaxSize()) {
        // Scrims
        Box(Modifier.fillMaxWidth().height(160.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(260.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
        )
        Column(Modifier.padding(start = 56.dp, top = 40.dp, end = 56.dp)) {
            Text(s.stream.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 1)
            if (s.stream.isSeries) {
                Text(
                    "S${e.season ?: 1}:E${e.episode ?: e.episodeNumber ?: ""}" + (e.name?.let { "  \"$it\"" } ?: ""),
                    style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary, maxLines = 1,
                )
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 56.dp, vertical = 36.dp)) {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val playPause = remember { FocusRequester() }
                SeekBar(position, duration, scrubbing, seekFocus, onSeekKey, onToggle, downTarget = playPause)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    ControlIcon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, onToggle, Modifier.focusRequester(playPause))
                    ControlIcon(Icons.Default.Replay10, { vm.player.seekBack(); onInteract() })
                    ControlIcon(Icons.Default.Forward10, { vm.player.seekForward(); onInteract() })
                    Spacer(Modifier.weight(1f))
                    if (s.stream.isSeries) {
                        ControlLabel(Icons.Default.VideoLibrary, "Episodes") { onPanel(Panel.Episodes) }
                        if (vm.hasNext()) ControlLabel(Icons.Default.SkipNext, "Next Episode") { vm.next() }
                    }
                    ControlLabel(Icons.Default.ClosedCaption, "Audio & Subtitles") { onPanel(Panel.AudioSubs) }
                    if (s.stream.episodes.any { it.sources.size > 1 } || s.source.byAudio.size > 1) {
                        ControlLabel(Icons.Default.HighQuality, s.source.label) { onPanel(Panel.Quality) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeekBar(
    position: Long,
    duration: Long,
    scrubbing: Boolean,
    focus: TrackedFocus,
    onSeekKey: (Boolean, Int) -> Unit,
    onToggle: () -> Unit,
    downTarget: FocusRequester,
) {
    var focused by remember { mutableStateOf(false) }
    val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val thumb by animateFloatAsState(if (focused) 1f else 0f, tween(150), label = "thumb")
    Column {
        if (scrubbing) {
            // Target time bubble above the thumb while scrubbing.
            Box(Modifier.fillMaxWidth().height(34.dp)) {
                Box(Modifier.fillMaxWidth(frac.coerceAtLeast(0.001f)), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        formatTime(position),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.weight(1f).height(22.dp)
                    .trackFocus(focus)
                    // Down from the timeline always goes to Play/Pause, like Netflix.
                    .focusProperties { down = downTarget }
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when (ev.key) {
                            Key.DirectionLeft -> { onSeekKey(false, ev.nativeKeyEvent.repeatCount); true }
                            Key.DirectionRight -> { onSeekKey(true, ev.nativeKeyEvent.repeatCount); true }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onToggle(); true }
                            else -> false
                        }
                    }
                    .focusable(),
                contentAlignment = Alignment.CenterStart,
            ) {
                val h = (4 + 3 * thumb).dp
                Box(Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f)))
                Box(Modifier.fillMaxWidth(frac).height(h).clip(RoundedCornerShape(2.dp)).background(Palette.Red))
                Box(Modifier.fillMaxWidth(frac), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size((10 + 8 * thumb).dp).clip(RoundedCornerShape(50)).background(Palette.Red))
                }
            }
            Spacer(Modifier.width(16.dp))
            Text(
                "-" + formatTime((duration - position).coerceAtLeast(0)),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
    ) { Icon(icon, null, Modifier.padding(10.dp).size(30.dp)) }
}

@Composable
private fun ControlLabel(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun PlayerPill(label: String, modifier: Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.55f), contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
        border = ClickableSurfaceDefaults.border(
            border = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Color.White.copy(alpha = 0.7f))),
        ),
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(22.dp)); Spacer(Modifier.width(8.dp)) }
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun SubtitleOverlay(subs: Map<String, List<Cue>>, style: SubtitleStyle, posMs: Long, modifier: Modifier) {
    if (style.language == "none" || subs.isEmpty()) return
    val lines = when (style.language) {
        "hybrid" -> listOfNotNull(subs["fa"]?.at(posMs)?.text, subs["en"]?.at(posMs)?.text)
        else -> listOfNotNull((subs[style.language] ?: subs.values.firstOrNull())?.at(posMs)?.text)
    }
    if (lines.isEmpty()) return
    val color = if (style.color == "yellow") Color(0xFFFFE14D) else Color.White
    val bg = when (style.background) {
        "black" -> Color.Black
        "semi" -> Color.Black.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Column(modifier.padding(horizontal = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        lines.forEach { text ->
            Text(
                text,
                color = color,
                fontSize = style.size.sp,
                fontFamily = com.seenama.tv.ui.theme.SubtitleFont,
                lineHeight = (style.size * 1.3f).sp,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge.copy(
                    textDirection = TextDirection.Content,
                    shadow = if (style.background == "none") androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 6f) else null,
                ),
                modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }
}

// ---- Side panels ----

@Composable
private fun PanelFrame(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(460.dp).background(Color(0xF2141414))
            // The remote stays inside an open panel; Back closes it.
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .padding(horizontal = 32.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(bottom = 12.dp))
        content()
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent, contentColor = if (selected) Color.White else Palette.TextSecondary,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "✓" else " ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(24.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
        }
    }
}

@Composable
private fun rememberPanelFocus(): TrackedFocus {
    val fr = remember { TrackedFocus() }
    LaunchedEffect(Unit) { fr.request() }
    return fr
}

@Composable
private fun EpisodesPanel(s: PlayerUi.Ready, onPick: (Episode) -> Unit) {
    val seasons = remember(s.stream) { s.stream.episodes.groupBy { it.season ?: 1 }.toSortedMap() }
    var season by remember { mutableStateOf(s.episode.season ?: seasons.keys.first()) }
    val keys = remember(seasons) { seasons.keys.toList() }
    val fr = rememberPanelFocus()
    val chipsState = androidx.compose.foundation.lazy.rememberLazyListState()
    var switchedByKey by remember { mutableStateOf(0) }
    // Keep the selected season chip visible, and after Left/Right put focus on the new season's episode.
    LaunchedEffect(season) { chipsState.animateScrollToItem(keys.indexOf(season).coerceAtLeast(0)) }
    LaunchedEffect(switchedByKey) { if (switchedByKey > 0) fr.request() }
    fun changeSeason(step: Int): Boolean {
        val next = keys.getOrNull(keys.indexOf(season) + step) ?: return true // at the first/last season: stay put
        season = next
        switchedByKey++
        return true
    }
    PanelFrame("Episodes") {
        if (seasons.size > 1) {
            Text("◀ ▶  change season", style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
            androidx.compose.foundation.lazy.LazyRow(state = chipsState, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(seasons.keys.toList().size) { i ->
                    val k = seasons.keys.toList()[i]
                    Surface(
                        onClick = { season = k },
                        modifier = Modifier.onFocusChanged { if (it.isFocused) season = k },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (k == season) Color.White.copy(alpha = 0.2f) else Color.Transparent,
                            contentColor = Color.White, focusedContainerColor = Color.White, focusedContentColor = Color.Black,
                        ),
                    ) { Text("Season $k", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) }
                }
            }
        }
        val list = seasons[season].orEmpty()
        androidx.compose.foundation.lazy.LazyColumn(
            // In the episode list, Left/Right switch seasons.
            Modifier.onPreviewKeyEvent { ev ->
                if (seasons.size < 2 || ev.type != KeyEventType.KeyDown) false
                else when (ev.key) {
                    Key.DirectionLeft -> changeSeason(-1)
                    Key.DirectionRight -> changeSeason(+1)
                    else -> false
                }
            },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(list.size, key = { list[it].fileId }) { i ->
                val ep = list[i]
                val current = ep.fileId == s.episode.fileId
                OptionRow(
                    "${ep.episode ?: ep.episodeNumber ?: i + 1}. " + (ep.name ?: "Episode ${ep.episode ?: i + 1}"),
                    current,
                    if (current || (i == 0 && list.none { it.fileId == s.episode.fileId })) Modifier.trackFocus(fr) else Modifier,
                ) { onPick(ep) }
            }
        }
    }
}

@Composable
private fun AudioSubsPanel(s: PlayerUi.Ready, vm: PlayerViewModel) {
    val audio by vm.audioOptions.collectAsStateWithLifecycle()
    val selectedAudio by vm.selectedAudio.collectAsStateWithLifecycle()
    val subs by vm.subtitles.collectAsStateWithLifecycle()
    val style by vm.subtitleStyle.collectAsStateWithLifecycle()
    val fr = rememberPanelFocus()
    PanelFrame("Audio & Subtitles") {
        androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item { Header("Audio") }
            if (audio.size > 1) {
                items(audio.size) { i ->
                    val o = audio[i]
                    OptionRow(o.label, o.language == selectedAudio, if (i == 0) Modifier.trackFocus(fr) else Modifier) { vm.selectAudio(o) }
                }
            } else item { Text("Only one audio track available", style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted, modifier = Modifier.padding(start = 14.dp)) }
            if (s.source.byAudio.size > 1) {
                item { Header("Audio channels") }
                items(s.source.byAudio.keys.toList().size) { i ->
                    val k = s.source.byAudio.keys.toList()[i]
                    val label = when (k) { "2ch" -> "Stereo (2.0)"; "6ch" -> "Surround (5.1)"; else -> "Auto" }
                    OptionRow(label, k == s.audioChannels) { vm.selectSource(s.source, k) }
                }
            }
            item { Header("Subtitles") }
            val langs = buildList {
                add("none" to "Off")
                if ("fa" in subs) add("fa" to "Persian")
                if ("en" in subs) add("en" to "English")
                if ("fa" in subs && "en" in subs) add("hybrid" to "Persian + English")
            }
            items(langs.size) { i ->
                val (k, label) = langs[i]
                OptionRow(label, style.language == k, if (audio.size <= 1 && i == 0) Modifier.trackFocus(fr) else Modifier) {
                    vm.updateSubtitleStyle(style.copy(language = k))
                }
            }
            item { Header("Subtitle size") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallButton("Smaller") { vm.updateSubtitleStyle(style.copy(size = (style.size - 4).coerceAtLeast(SubtitleStyle.MIN_SIZE))) }
                    SmallButton("${style.size}") {}
                    SmallButton("Larger") { vm.updateSubtitleStyle(style.copy(size = (style.size + 4).coerceAtMost(SubtitleStyle.MAX_SIZE))) }
                }
            }
            item { Header("Color") }
            item { OptionRow("White", style.color == "white") { vm.updateSubtitleStyle(style.copy(color = "white")) } }
            item { OptionRow("Yellow", style.color == "yellow") { vm.updateSubtitleStyle(style.copy(color = "yellow")) } }
            item { Header("Background") }
            item { OptionRow("Black", style.background == "black") { vm.updateSubtitleStyle(style.copy(background = "black")) } }
            item { OptionRow("Semi-transparent", style.background == "semi") { vm.updateSubtitleStyle(style.copy(background = "semi")) } }
            item { OptionRow("None", style.background == "none") { vm.updateSubtitleStyle(style.copy(background = "none")) } }
        }
    }
}

@Composable
private fun QualityPanel(s: PlayerUi.Ready, onPick: (com.seenama.tv.data.Source, String) -> Unit) {
    val fr = rememberPanelFocus()
    PanelFrame("Video quality") {
        s.episode.sources.forEach { src ->
            val selected = src.label == s.source.label
            OptionRow(if (src.label.equals("auto", true)) "Auto" else src.label, selected, if (selected) Modifier.trackFocus(fr) else Modifier) {
                onPick(src, s.audioChannels)
            }
        }
    }
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TextMuted, modifier = Modifier.padding(start = 14.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun SmallButton(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
    ) { Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) }
}

private fun formatTime(ms: Long): String {
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
