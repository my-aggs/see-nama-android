package com.seenama.tv.ui.details

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seenama.tv.data.Episode
import com.seenama.tv.data.ListGroup
import com.seenama.tv.data.StreamInfo
import com.seenama.tv.data.TitleDetails
import com.seenama.tv.data.isFarsi
import com.seenama.tv.data.ShowInfo
import com.seenama.tv.data.EpisodeMeta
import com.seenama.tv.data.WikiSummary
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.CenterLoading
import com.seenama.tv.ui.components.ErrorState
import com.seenama.tv.ui.components.MetaLine
import com.seenama.tv.ui.components.PivotScroll
import com.seenama.tv.ui.components.SizedImage
import com.seenama.tv.ui.components.cardBorder
import com.seenama.tv.ui.components.cardColors
import com.seenama.tv.ui.components.rememberFocusMemory
import com.seenama.tv.ui.components.requestWhenReady
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun DetailsScreen(id: Long, nav: Nav) {
    val vm: DetailsViewModel = viewModel(factory = DetailsViewModel.factory(id))
    val details by vm.details.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val d = details
    when {
        d != null -> DetailsContent(d, vm, nav)
        error -> ErrorState("Couldn't load this title") { vm.load() }
        else -> CenterLoading()
    }
}

@Composable
private fun DetailsContent(d: TitleDetails, vm: DetailsViewModel, nav: Nav) {
    val t = d.title
    val library by vm.library.collectAsStateWithLifecycle()
    val cw by vm.continueWatching.collectAsStateWithLifecycle()
    val stream by vm.stream.collectAsStateWithLifecycle()
    val show by vm.show.collectAsStateWithLifecycle()
    val wiki by vm.wiki.collectAsStateWithLifecycle()
    val similar by vm.similar.collectAsStateWithLifecycle()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val resume = cw.firstOrNull { it.id == t.id }
    val progressMap by vm.repo.watchProgress.collectAsStateWithLifecycle()
    val local = progressMap[t.id]?.takeIf { !it.isFinished || t.isSeries }
    val memory = rememberFocusMemory()
    val playFocus = remember { FocusRequester() }
    val canStream = vm.repo.canStream(t)

    LaunchedEffect(Unit) {
        if (memory.hasSaved) return@LaunchedEffect
        playFocus.requestWhenReady()
    }

    val play: (Long) -> Unit = { fileId -> if (canStream) nav.player(t.id, t.freeStream, fileId) else nav.login() }

    Box(Modifier.fillMaxSize()) {
        DetailsBackdrop(t.cover ?: t.poster)
        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        var headerFocused by remember { androidx.compose.runtime.mutableStateOf(false) }
        // The initial focus pass (and the episodes row appearing) can scroll the list; keep the header pinned.
        LaunchedEffect(headerFocused, stream) {
            if (headerFocused) { kotlinx.coroutines.delay(60); listState.scrollToItem(0) }
        }
        val pageScroll = remember { com.seenama.tv.ui.components.VisibleOrPivotSpec(0.3f) }
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides pageScroll) {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 120.dp)) {
                item(key = "header") {
                    Column(
                        Modifier.padding(start = 64.dp, top = 48.dp, end = 64.dp)
                            // Keep the header pinned at the top while its buttons are focused.
                            .onFocusChanged {
                                headerFocused = it.hasFocus
                                if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) }
                            },
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(t.displayTitle, style = MaterialTheme.typography.displaySmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.55f))
                        if (t.isFarsi && t.titleFa != null) Text(t.titleFa, style = MaterialTheme.typography.titleLarge, color = Palette.TextSecondary)
                        MetaLine(t, showRatings = false)
                        RatingsStrip(t, show)
                        AiringLine(t, show)?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = Palette.Match) }
                        if (t.genres.isNotEmpty()) Text(t.genres.joinToString("  •  ") { it.name }, style = MaterialTheme.typography.titleSmall, color = Palette.TextSecondary)
                        t.plot?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f), maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.55f)) }
                        val credits = buildList {
                            t.director?.let { add("Director: $it") }
                            if (d.countries.isNotEmpty()) add("Country: " + d.countries.joinToString(", "))
                        }.joinToString("   |   ")
                        if (credits.isNotEmpty()) Text(credits, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (d.cast.isNotEmpty()) Text("Cast: " + d.cast.take(4).joinToString(", ") { it.name }, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        Row(
                            // Coming back up from the episodes/rows below always lands on Resume/Play.
                            Modifier.focusProperties {
                                @Suppress("DEPRECATION")
                                enter = { if (it == androidx.compose.ui.focus.FocusDirection.Up) playFocus else FocusRequester.Default }
                            },
                            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val playLabel = when {
                                !canStream -> "Sign in to Watch"
                                resume?.cwSeason != null -> "Resume S${resume.cwSeason}:E${resume.cwEpisode}"
                                resume != null -> "Resume"
                                else -> "Play"
                            }
                            ActionButton(
                                playLabel, Icons.Default.PlayArrow, { play(-1) },
                                memory.modifier("play").focusRequester(playFocus).onFocusChanged { if (it.isFocused) vm.warm(-1, settleMs = 300) },
                            )
                            if (!t.isFarsi) ActionButton("Trailer", Icons.Default.Theaters, { openTrailer(ctx, t) }, memory.modifier("trailer"))
                            if (vm.repo.isLoggedIn && !t.isFarsi) {
                                val inList = t.id in library.watchlist
                                ActionButton(if (inList) "My List" else "My List", if (inList) Icons.Default.Check else Icons.Default.Add, { vm.toggle(ListGroup.Watchlist) }, memory.modifier("list"))
                                val fav = t.id in library.favorite
                                ActionButton("", if (fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, { vm.toggle(ListGroup.Favorite) }, memory.modifier("fav"))
                                if (t.isSeries) {
                                    val notify = t.id in library.notify
                                    ActionButton("", if (notify) Icons.Default.NotificationsActive else Icons.Default.Notifications, { vm.toggle(ListGroup.Notify) }, memory.modifier("notify"))
                                }
                            }
                        }
                        // Netflix-style "where you left off": progress bar + time left.
                        if (canStream && local != null && local.positionMs > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(220.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.25f))) {
                                    Box(Modifier.fillMaxWidth(local.fraction.coerceAtLeast(0.02f)).height(4.dp).background(Palette.Red))
                                }
                                Spacer(Modifier.width(12.dp))
                                val left = ((local.durationMs - local.positionMs) / 60_000).coerceAtLeast(1)
                                Text(
                                    (if (local.season != null) "S${local.season}:E${local.episode} · " else "") + "${left}m left",
                                    style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary,
                                )
                            }
                        }
                        if (!canStream) Text("An active subscription is required to watch this title.", style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
                    }
                }
                val s = stream
                if (s != null && s.isSeries) {
                    item(key = "episodes") {
                        EpisodesSection(s, t.cover, resume, show, memory, onFocusEpisode = { vm.warm(it.fileId, settleMs = 600) }) { play(it.fileId) }
                    }
                }
                if (similar.isNotEmpty()) {
                    item(key = "similar") { MoreLikeThis(similar, memory) { nav.details(it.id) } }
                }
                item(key = "about") { AboutSection(d, show, wiki) }
                val cast = mergedCast(d, show)
                if (cast.isNotEmpty()) {
                    item(key = "cast") { CastRow(cast) }
                }
            }
        }
    }
}

@Composable
private fun DetailsBackdrop(url: String?) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxWidth * 9f / 16f
        SizedImage(url, w, h, Modifier.fillMaxWidth().height(h).align(Alignment.TopCenter))
        val side = if (rtl) Brush.horizontalGradient(0.15f to Color.Transparent, 0.75f to Palette.Background)
        else Brush.horizontalGradient(0.25f to Palette.Background, 0.85f to Color.Transparent)
        Box(Modifier.fillMaxSize().background(side))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 0.95f to Palette.Background)))
    }
}

@Composable
private fun EpisodesSection(
    s: StreamInfo,
    art: String?,
    resume: com.seenama.tv.data.Title?,
    show: ShowInfo?,
    memory: com.seenama.tv.ui.components.FocusMemory,
    onFocusEpisode: (Episode) -> Unit,
    onPlay: (Episode) -> Unit,
) {
    val initialSeason = resume?.cwSeason?.toIntOrNull()
    val seasons = remember(s) { s.episodes.groupBy { it.season ?: 1 }.toSortedMap() }
    val keys = seasons.keys.toList()
    var season by rememberSaveable { mutableIntStateOf(initialSeason?.takeIf { it in seasons } ?: keys.first()) }
    Column(Modifier.padding(top = 36.dp)) {
        Text("Episodes", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(start = 64.dp, bottom = 12.dp))
        if (keys.size > 1) {
            val selectedChip = remember { FocusRequester() }
            LazyRow(
                Modifier.focusRestorer { selectedChip },
                contentPadding = PaddingValues(horizontal = 64.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(keys, key = { it }) { k ->
                    SeasonChip(
                        "Season $k", k == season,
                        memory.modifier("season:$k").then(if (k == season) Modifier.focusRequester(selectedChip) else Modifier)
                            .onFocusChanged { if (it.isFocused) season = k },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
        }
        val episodes = seasons[season].orEmpty()
        fun isCurrent(e: Episode) = resume?.cwSeason?.toIntOrNull() == (e.season ?: 1) && resume.cwEpisode?.toIntOrNull() == (e.episode ?: e.episodeNumber)
        val targetIndex = episodes.indexOfFirst(::isCurrent).coerceAtLeast(0)
        // Each season starts scrolled to the episode you're on (or its first episode)…
        val rowState = rememberSaveable(season, saver = androidx.compose.foundation.lazy.LazyListState.Saver) {
            androidx.compose.foundation.lazy.LazyListState(firstVisibleItemIndex = targetIndex)
        }
        val targetFocus = remember(season) { FocusRequester() }
        var targetAttached by remember(season) { androidx.compose.runtime.mutableStateOf(false) }
        PivotScroll(parentFraction = 0f, horizontal = true, startOffset = 64.dp) {
            LazyRow(
                // …and coming down from the seasons (or up from below) lands on that episode, not whatever is underneath.
                Modifier.focusProperties {
                    @Suppress("DEPRECATION")
                    enter = { if (targetAttached) targetFocus else FocusRequester.Default }
                },
                state = rowState,
                contentPadding = PaddingValues(horizontal = 64.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(episodes, key = { _, e -> e.fileId }) { i, e ->
                    val meta = show?.episode(e.season, e.episode ?: e.episodeNumber)
                    val target = i == targetIndex
                    if (target) androidx.compose.runtime.DisposableEffect(Unit) { targetAttached = true; onDispose { targetAttached = false } }
                    EpisodeCard(
                        e, meta, art, if (isCurrent(e)) resume?.cwProgress else null, { onPlay(e) },
                        memory.modifier("ep:${e.fileId}")
                            .then(if (target) Modifier.focusRequester(targetFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) onFocusEpisode(e) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, modifier: Modifier) {
    Surface(
        onClick = {},
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.Transparent,
            contentColor = if (selected) Color.White else Palette.TextSecondary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) { Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
}

@Composable
private fun EpisodeCard(e: Episode, meta: EpisodeMeta?, art: String?, progress: Float?, onClick: () -> Unit, modifier: Modifier) {
    val w = 300.dp
    val number = e.episode ?: e.episodeNumber
    Column(modifier.width(w)) {
        Card(
            onClick = onClick,
            modifier = Modifier.width(w).aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(6.dp)),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = cardBorder(),
            colors = cardColors(),
        ) {
            Box(Modifier.fillMaxSize()) {
                // Real episode still from TVmaze when available, otherwise the show's artwork.
                SizedImage(e.thumb ?: meta?.image ?: art, w, w * 9f / 16f, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))))
                Text(
                    (number ?: 0).toString(),
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                )
                meta?.runtimeMin?.let {
                    Text("${it}m", style = MaterialTheme.typography.labelMedium, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp))
                }
                if (progress != null) {
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f)))
                    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0.03f, 1f)).height(4.dp).background(Palette.Red))
                }
            }
        }
        Text(
            meta?.name ?: e.name ?: "Episode ${number ?: ""}",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        meta?.summary?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        meta?.airdate?.let { Text(formatDate(it), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, modifier = Modifier.padding(top = 2.dp)) }
    }
}

@Composable
private fun RatingsStrip(t: com.seenama.tv.data.Title, show: ShowInfo?) {
    val chips = buildList {
        t.imdb?.let { add(Triple("IMDb", "%.1f".format(it) + (t.imdbVotes?.let { v -> "  (${compact(v)})" } ?: ""), Palette.Imdb)) }
        t.rottenTomatoes?.let { add(Triple("Rotten Tomatoes", "$it%", if (it >= 60) Color(0xFFFA320A) else Color(0xFF8BC34A))) }
        t.metacritic?.let { add(Triple("Metacritic", "$it", when { it >= 61 -> Color(0xFF66CC33); it >= 40 -> Color(0xFFFFCC33); else -> Color(0xFFFF0000) })) }
        t.myAnimeList?.let { add(Triple("MyAnimeList", "%.2f".format(it), Color(0xFF2E51A2))) }
        if (t.imdb == null) show?.rating?.let { add(Triple("TVmaze", "%.1f".format(it), Color(0xFF3C948B))) }
    }
    if (chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        chips.forEach { (label, value, color) ->
            Row(
                Modifier.clip(RoundedCornerShape(4.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color))
                Spacer(Modifier.width(6.dp))
                Text("$label ", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
                Text(value, style = MaterialTheme.typography.labelLarge, color = Color.White)
            }
        }
    }
}

/** "Returning series · New episode Wed, Oct 21" / "Ended · 2016–2022". */
private fun AiringLine(t: com.seenama.tv.data.Title, show: ShowInfo?): String? {
    if (!t.isSeries) return null
    val next = show?.nextEpisode
    val nextMs = next?.airstampMs ?: t.nextEpisodeMs
    val network = show?.network ?: t.network
    return when {
        nextMs != null && nextMs > System.currentTimeMillis() - 86_400_000 -> {
            val day = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US).format(java.util.Date(nextMs))
            val ep = next?.let { " (S${it.season}:E${it.number})" } ?: ""
            "New episode $day$ep" + (network?.let { " · $it" } ?: "")
        }
        show?.status == "Ended" -> "Ended" + listOfNotNull(show.premiered?.take(4), show.ended?.take(4)).joinToString("–", " · ") + (network?.let { " · $it" } ?: "")
        show?.status == "Running" || t.airStatus == "Continuing" -> "Returning series" + (network?.let { " · $it" } ?: "")
        else -> null
    }
}

@Composable
private fun MoreLikeThis(items: List<com.seenama.tv.data.Title>, memory: com.seenama.tv.ui.components.FocusMemory, onClick: (com.seenama.tv.data.Title) -> Unit) {
    Column(Modifier.padding(top = 36.dp)) {
        Text("More Like This", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(start = 64.dp, bottom = 12.dp))
        PivotScroll(parentFraction = 0f, horizontal = true, startOffset = 64.dp) {
            LazyRow(contentPadding = PaddingValues(horizontal = 64.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(items, key = { it.id }) { s ->
                    com.seenama.tv.ui.components.LandscapeCard(
                        s, onClick = { onClick(s) }, onFocused = {}, width = 220.dp,
                        subtitle = listOfNotNull(s.year, s.imdb?.let { "IMDb %.1f".format(it) }).joinToString(" · "),
                        modifier = memory.modifier("similar:${s.id}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun AboutSection(d: TitleDetails, show: ShowInfo?, wiki: WikiSummary?) {
    val t = d.title
    val facts = buildList {
        t.director?.let { add("Director" to it) }
        (show?.network ?: t.network)?.let { add("Network" to it) }
        show?.premiered?.let { add("Premiered" to formatDate(it)) }
        show?.status?.let { add("Status" to it) }
        if (d.languages.isNotEmpty()) add("Language" to d.languages.joinToString(", "))
        if (d.countries.isNotEmpty()) add("Country" to d.countries.joinToString(", "))
        t.age?.let { add("Maturity rating" to it) }
        if (t.genres.isNotEmpty()) add("Genres" to t.genres.joinToString(", ") { it.name })
    }
    if (wiki == null && facts.isEmpty()) return
    Column(Modifier.padding(start = 64.dp, end = 64.dp, top = 36.dp)) {
        Text("About ${t.displayTitle}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Column(Modifier.weight(1.4f)) {
                wiki?.let {
                    Text(it.extract, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f), maxLines = 8, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(6.dp))
                    Text("Source: Wikipedia", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                facts.forEach { (k, v) ->
                    Row {
                        Text("$k: ", style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
                        Text(v, style = MaterialTheme.typography.bodyMedium, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            wiki?.let {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    com.seenama.tv.ui.components.QrCode(it.url, 120.dp)
                    Spacer(Modifier.height(6.dp))
                    Text("Read more\non your phone", style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
    }
}

/** 30nama's cast list, with photos backfilled from TVmaze by name. */
private fun mergedCast(d: TitleDetails, show: ShowInfo?): List<com.seenama.tv.data.Person> {
    val tv = show?.cast.orEmpty().associateBy { it.name.lowercase() }
    val base = d.cast.ifEmpty { show?.cast.orEmpty() }
    return base.map { p -> if (p.image == null) p.copy(image = tv[p.name.lowercase()]?.image) else p }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "${n / 1_000}K"
    else -> "$n"
}

private fun formatDate(iso: String): String = runCatching {
    java.time.LocalDate.parse(iso.take(10)).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.US))
}.getOrDefault(iso)

/** Opens the YouTube app on Android TV with a trailer search; falls back to any browser/YouTube handler. */
private fun openTrailer(ctx: android.content.Context, t: com.seenama.tv.data.Title) {
    val query = "${t.displayTitle} ${t.year ?: ""} official trailer".trim()
    val attempts = listOf(
        android.content.Intent(android.content.Intent.ACTION_SEARCH).setPackage("com.google.android.youtube.tv").putExtra(android.app.SearchManager.QUERY, query),
        android.content.Intent(android.content.Intent.ACTION_SEARCH).setPackage("com.google.android.youtube").putExtra(android.app.SearchManager.QUERY, query),
        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/results?search_query=" + android.net.Uri.encode(query))),
    )
    for (i in attempts) {
        if (runCatching { ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    android.widget.Toast.makeText(ctx, "Install YouTube to watch trailers", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun CastRow(cast: List<com.seenama.tv.data.Person>) {
    Column(Modifier.padding(top = 36.dp)) {
        Text("Cast", style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(start = 64.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 64.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            items(cast.take(20), key = { it.name }) { p ->
                Surface(
                    onClick = {},
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.White.copy(alpha = 0.12f)),
                ) {
                    Column(Modifier.width(110.dp).padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(84.dp).clip(CircleShape).background(Palette.SurfaceHigh), contentAlignment = Alignment.Center) {
                            if (p.image != null) SizedImage(p.image, 84.dp, 84.dp, Modifier.fillMaxSize())
                            else Text(
                                p.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) },
                                style = MaterialTheme.typography.titleLarge, color = Palette.TextSecondary,
                            )
                        }
                        Text(p.name, style = MaterialTheme.typography.labelLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        p.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}
