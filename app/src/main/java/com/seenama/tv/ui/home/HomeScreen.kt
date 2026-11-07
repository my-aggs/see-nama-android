package com.seenama.tv.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.data.Row as HomeRow
import com.seenama.tv.data.Title
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.CenterLoading
import com.seenama.tv.ui.components.ErrorState
import com.seenama.tv.ui.components.FocusMemory
import com.seenama.tv.ui.components.LocalInitialFocus
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import com.seenama.tv.ui.components.LandscapeCard
import com.seenama.tv.ui.components.RankedCard
import com.seenama.tv.ui.components.MetaLine
import com.seenama.tv.ui.components.PivotScroll
import com.seenama.tv.ui.components.SizedImage
import com.seenama.tv.ui.components.cardBorder
import com.seenama.tv.ui.components.cardColors
import com.seenama.tv.ui.components.rememberFocusMemory
import com.seenama.tv.ui.components.throttleKeyRepeat
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

enum class HomeFilter { All, Series, Movies, Farsi }

private val CardWidth = 176.dp
private val PosterWidth = 124.dp
/** Width of the rank number area to the left of each Top 10 poster (RankedCard: 1.9 × 100dp − 100dp). */
private val RankNumberWidth = 90.dp
private val RowGap = 14.dp

@Composable
fun HomeScreen(nav: Nav, filter: HomeFilter) {
    val vm: HomeViewModel = viewModel(key = "home-$filter", factory = HomeViewModel.factory(filter))
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        HomeState.Loading -> CenterLoading()
        is HomeState.Error -> ErrorState("Couldn't load this page") { vm.load() }
        is HomeState.Ready -> HomeContent(s, nav, vm)
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun HomeContent(s: HomeState.Ready, nav: Nav, vm: HomeViewModel) {
    val memory = rememberFocusMemory()
    val playFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val initial = LocalInitialFocus.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        if (s.hero.isNotEmpty()) initial.requester = playFocus
        onDispose { if (initial.requester === playFocus) initial.requester = null }
    }
    var focused by remember { mutableStateOf(s.hero.firstOrNull() ?: s.rows.firstOrNull()?.items?.firstOrNull()) }
    var heroFocused by remember { mutableStateOf(!memory.hasSaved) }
    var heroIndex by remember { mutableStateOf(0) }
    var focusedRow by remember { mutableStateOf(0) }
    val hero = s.hero.getOrNull(heroIndex)

    // Prefetch / pre-buffer whatever focus settles on.
    val latest by androidx.compose.runtime.rememberUpdatedState(s)
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(heroFocused, if (heroFocused) latest.hero.getOrNull(heroIndex) else focused, focusedRow) }.collect { (onHero, t, row) ->
            if (t == null) return@collect
            val isResumeRow = !onHero && latest.rows.getOrNull(row)?.key == "continue"
            vm.onFocusSettled(t, startsPlayback = onHero || isResumeRow)
        }
    }
    // The info panel follows focus once it settles for a moment, so holding a key doesn't re-layout text every press.
    var info by remember { mutableStateOf(focused) }
    LaunchedEffect(Unit) { snapshotFlow { focused }.debounce(90).collect { info = it } }
    // Backdrop follows focus, but only after focus settles so fast scrolling doesn't thrash image loads.
    var backdrop by remember { mutableStateOf(focused) }
    LaunchedEffect(Unit) {
        snapshotFlow { if (heroFocused) hero else focused }.debounce(220).collect { backdrop = it }
    }
    // Rotate the billboard while the user is idling on it.
    LaunchedEffect(heroFocused, s.hero.size) {
        if (!heroFocused || s.hero.size < 2) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(9000)
            heroIndex = (heroIndex + 1) % s.hero.size
        }
    }

    val screenH = LocalConfiguration.current.screenHeightDp.dp
    // The billboard fades out as soon as focus moves into the rows (its info moves to the fixed panel).
    val heroAlpha by androidx.compose.animation.core.animateFloatAsState(if (heroFocused) 1f else 0f, tween(200), label = "hero")
    Box(Modifier.fillMaxSize()) {
        Backdrop(backdrop)

        // Fixed info panel for the focused card (Netflix's "billboard" collapses into this).
        AnimatedVisibility(!heroFocused, enter = fadeIn(tween(200)), exit = fadeOut(tween(120))) {
            info?.let { InfoPanel(it, Modifier.padding(start = 48.dp, top = 36.dp).fillMaxWidth(0.5f), compact = true) }
        }

        PivotScroll(parentFraction = 0.48f) {
            LazyColumn(
                Modifier.fillMaxSize().throttleKeyRepeat(),
                contentPadding = PaddingValues(bottom = screenH * 0.5f),
                verticalArrangement = Arrangement.spacedBy(RowGap),
            ) {
                if (hero != null) {
                    item(key = "hero", contentType = "hero") {
                        HeroBlock(
                            hero, s.hero.size, heroIndex,
                            modifier = Modifier.height(screenH * 0.72f)
                                .graphicsLayer { alpha = heroAlpha }
                                .onFocusChanged { if (it.hasFocus) heroFocused = true },
                            memory = memory,
                            playFocus = playFocus,
                            onPlay = { vm.play(hero, nav) },
                            onInfo = { nav.details(hero.id) },
                        )
                    }
                }
                itemsIndexed(s.rows, key = { _, r -> r.key }, contentType = { _, _ -> "row" }) { index, row ->
                    // Rows that have scrolled up behind the info panel are hidden, like Netflix.
                    // (No fade: an animated alpha forces an offscreen layer per row on weak GPUs.)
                    // State is read inside the layer block, so focus moving between rows only
                    // re-runs this draw-time lambda instead of recomposing every visible row.
                    val rowModifier = remember(index) {
                        Modifier.graphicsLayer { alpha = if (!heroFocused && index < focusedRow) 0f else 1f }
                            .onFocusChanged { if (it.hasFocus) { heroFocused = false; focusedRow = index } }
                    }
                    TitleRow(
                        row, memory,
                        modifier = rowModifier,
                        onFocus = { focused = it },
                        onClick = { nav.details(it.id) },
                        onResume = { vm.play(it, nav) },
                        onSeeAll = row.archiveSlug?.let { slug -> { nav.archive(slug, row.title) } },
                    )
                }
            }
        }
    }
}

@Composable
private fun Backdrop(title: Title?) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth * 0.72f
        val h = w * 9f / 16f
        // Gradients are painted only over the artwork (not as extra full-screen layers):
        // weak TV GPUs are fill-rate bound, so every full-screen blend costs a lot.
        val fade = Modifier.width(w).height(h).drawWithCache {
            val side = if (rtl) Brush.horizontalGradient(0.55f to Color.Transparent, 1f to Palette.Background)
            else Brush.horizontalGradient(0f to Palette.Background, 0.3f to Palette.Background.copy(alpha = 0.75f), 0.65f to Color.Transparent)
            val bottom = Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.Background)
            onDrawWithContent { drawContent(); drawRect(side); drawRect(bottom) }
        }
        Crossfade(title?.landscape, animationSpec = tween(350), label = "backdrop", modifier = Modifier.align(Alignment.TopEnd)) { url ->
            if (url != null) SizedImage(url, w, h, fade)
        }
    }
}

@Composable
private fun InfoPanel(t: Title, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
        Text(
            t.displayTitle,
            style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall,
            color = Color.White, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
        )
        MetaLine(t)
        t.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary, maxLines = if (compact) 2 else 3, overflow = TextOverflow.Ellipsis) }
        if (!compact && t.genres.isNotEmpty()) {
            Text(t.genres.take(4).joinToString("  •  ") { it.name }, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun HeroBlock(
    t: Title,
    count: Int,
    index: Int,
    modifier: Modifier,
    memory: FocusMemory,
    playFocus: androidx.compose.ui.focus.FocusRequester,
    onPlay: () -> Unit,
    onInfo: () -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 36.dp).fillMaxWidth(0.5f),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Crossfade(t, animationSpec = tween(350), label = "heroText") { InfoPanel(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ActionButton("Play", Icons.Default.PlayArrow, onPlay, memory.modifier("hero:play").focusRequester(playFocus))
                ActionButton("More Info", Icons.Default.Info, onInfo, memory.modifier("hero:info"))
            }
            if (count > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    repeat(count) { i ->
                        Box(
                            Modifier.size(width = if (i == index) 20.dp else 8.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                                .background(if (i == index) Color.White else Color.White.copy(alpha = 0.35f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleRow(
    row: HomeRow,
    memory: FocusMemory,
    modifier: Modifier,
    onFocus: (Title) -> Unit,
    onClick: (Title) -> Unit,
    onResume: (Title) -> Unit,
    onSeeAll: (() -> Unit)?,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            row.title,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            modifier = Modifier.padding(start = 48.dp, bottom = 10.dp),
        )
        // Pivot near the start edge: the focused card stays put and the row slides beneath it.
        // Ranked tiles are focused on their poster; keep room for the rank number to its left.
        PivotScroll(parentFraction = 0f, horizontal = true, startOffset = if (row.ranked) 48.dp + RankNumberWidth else 48.dp) {
            // Scroll position is tied to the row's first title: when a row is reordered (e.g. Continue
            // Watching after you watch something) it starts from the beginning again instead of staying
            // anchored to a card that moved, which used to leave the real first card unreachable.
            val rowState = androidx.compose.runtime.saveable.rememberSaveable(row.items.firstOrNull()?.id, saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
                androidx.compose.foundation.lazy.LazyListState()
            }
            LazyRow(
                // Hide cards that scrolled past the focused one (leave room for the focus scale-up).
                Modifier.drawWithContent { clipRect(left = 40.dp.toPx()) { this@drawWithContent.drawContent() } },
                state = rowState,
                contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (row.ranked) {
                    itemsIndexed(row.items, key = { _, t -> t.id }, contentType = { _, _ -> "ranked" }) { i, t ->
                        RankedCard(i + 1, t, { onClick(t) }, { onFocus(t) }, memory.modifier("${row.key}:${t.id}"))
                    }
                } else if (row.portrait) {
                    itemsIndexed(row.items, key = { _, t -> t.id }, contentType = { _, _ -> "poster" }) { _, t ->
                        com.seenama.tv.ui.components.PosterCard(t, { onClick(t) }, memory.modifier("${row.key}:${t.id}"), width = PosterWidth, onFocused = { onFocus(t) })
                    }
                } else itemsIndexed(row.items, key = { _, t -> t.id }, contentType = { _, _ -> "card" }) { _, t ->
                    val isResume = row.key == "continue"
                    LandscapeCard(
                        t,
                        // Continue Watching resumes straight away (long-press for details), like Netflix.
                        onClick = { if (isResume) onResume(t) else onClick(t) },
                        onLongClick = if (isResume) ({ onClick(t) }) else null,
                        progress = if (isResume) t.cwProgress else null,
                        onFocused = { onFocus(t) },
                        width = CardWidth,
                        subtitle = continueLabel(t),
                        modifier = memory.modifier("${row.key}:${t.id}"),
                    )
                }
                if (onSeeAll != null) {
                    item(key = "more", contentType = "more") { SeeAllCard(onSeeAll, memory.modifier("${row.key}:more"), tall = row.portrait) }
                }
            }
        }
    }
}

private fun continueLabel(t: Title): String? = when {
    t.cwSeason != null && t.cwEpisode != null -> "S${t.cwSeason}:E${t.cwEpisode}"
    else -> null
}

@Composable
private fun SeeAllCard(onClick: () -> Unit, modifier: Modifier, tall: Boolean = false) {
    Card(
        onClick = onClick,
        modifier = if (tall) modifier.width(PosterWidth).aspectRatio(2f / 3f) else modifier.width(CardWidth).aspectRatio(16f / 9f),
        shape = CardDefaults.shape(RoundedCornerShape(6.dp)),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = cardBorder(),
        colors = cardColors(),
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text("See All", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
    }
}
