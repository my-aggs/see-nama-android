package com.seenama.tv.ui.news

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.data.NewsItem
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.browse.SortChip
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.CenterLoading
import com.seenama.tv.ui.components.ErrorState
import com.seenama.tv.ui.components.QrCode
import com.seenama.tv.ui.components.SizedImage
import com.seenama.tv.ui.components.cardBorder
import com.seenama.tv.ui.components.cardColors
import com.seenama.tv.ui.components.rememberFocusMemory
import com.seenama.tv.ui.components.rememberInitialFocus
import com.seenama.tv.ui.components.requestWhenReady
import com.seenama.tv.ui.theme.Palette

/** Entertainment news from free RSS feeds, with "read on your phone" and "find on 30nama". */
@Composable
fun NewsScreen(nav: Nav, onSearch: (String) -> Unit) {
    val discovery = LocalContext.current.app.discovery
    var items by remember { mutableStateOf<List<NewsItem>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }
    var source by rememberSaveable { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<NewsItem?>(null) }
    // The story that was opened gets focus back when the reader closes.
    var lastOpened by remember { mutableStateOf<String?>(null) }
    val storyFocus = remember { FocusRequester() }
    var restore by remember { mutableStateOf(0) }
    LaunchedEffect(restore) { if (restore > 0) storyFocus.requestWhenReady() }
    fun close() { open = null; restore++ }
    val first = rememberInitialFocus()
    val memory = rememberFocusMemory()

    LaunchedEffect(attempt) {
        failed = false
        val n = runCatching { discovery.news() }.getOrDefault(emptyList())
        if (n.isEmpty()) failed = true else items = n
    }
    BackHandler(open != null) { close() }

    val all = items
    when {
        all == null && failed -> ErrorState("Couldn't load the news") { attempt++ }
        all == null -> CenterLoading()
        else -> Box(Modifier.fillMaxSize()) {
            val shown = all.filter { source == null || it.source == source }
            val sources = discovery.feeds.map { it.name }.filter { s -> all.any { it.source == s } }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 80.dp),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Column {
                        Text("News", style = MaterialTheme.typography.displaySmall, color = Color.White)
                        Text("The latest from Hollywood and beyond", style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
                        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SortChip("All", source == null, memory.modifier("src:all").focusRequester(first)) { source = null }
                            sources.forEach { s -> SortChip(s, s == source, memory.modifier("src:$s")) { source = s } }
                        }
                    }
                }
                shown.firstOrNull()?.let { lead ->
                    item(span = { GridItemSpan(maxLineSpan) }, key = "lead-${lead.link}") {
                        val m = memory.modifier("n:${lead.link}")
                        LeadStory(lead, if (lead.link == lastOpened) m.focusRequester(storyFocus) else m) { open = lead; lastOpened = lead.link }
                    }
                }
                items(shown.drop(1), key = { it.link }) { n ->
                    val m = memory.modifier("n:${n.link}")
                    NewsCard(n, if (n.link == lastOpened) m.focusRequester(storyFocus) else m) { open = n; lastOpened = n.link }
                }
            }
            AnimatedVisibility(
                open != null,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = slideInHorizontally(tween(250)) { it / 3 } + fadeIn(tween(250)),
                exit = slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(200)),
            ) {
                open?.let { Reader(it, onSearch) { close() } }
            }
        }
    }
}

private fun meta(n: NewsItem): String = listOfNotNull(
    n.source,
    n.dateMs?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() },
).joinToString("  ·  ")

@Composable
private fun LeadStory(n: NewsItem, modifier: Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(300.dp),
        shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f),
        border = cardBorder(),
        colors = cardColors(),
    ) {
        Box(Modifier.fillMaxSize()) {
            SizedImage(n.image, 900.dp, 300.dp, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.6f to Color.Black.copy(alpha = 0.4f), 1f to Color.Transparent)))
            Column(Modifier.align(Alignment.CenterStart).fillMaxWidth(0.55f).padding(32.dp)) {
                Text("TOP STORY", style = MaterialTheme.typography.labelLarge, color = Palette.Red)
                Spacer(Modifier.height(8.dp))
                Text(n.title, style = MaterialTheme.typography.headlineMedium, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                n.summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                Spacer(Modifier.height(8.dp))
                Text(meta(n), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
            }
        }
    }
}

@Composable
private fun NewsCard(n: NewsItem, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
            scale = CardDefaults.scale(focusedScale = 1.05f),
            border = cardBorder(),
            colors = cardColors(),
        ) {
            Box(Modifier.fillMaxSize().background(Palette.SurfaceHigh)) {
                if (n.image != null) SizedImage(n.image, 420.dp, 236.dp, Modifier.fillMaxSize())
                else Text(n.source, style = MaterialTheme.typography.headlineSmall, color = Palette.TextMuted, modifier = Modifier.align(Alignment.Center))
            }
        }
        Text(n.title, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(meta(n), style = MaterialTheme.typography.labelSmall, color = Palette.TextMuted)
    }
}

@Composable
private fun Reader(n: NewsItem, onSearch: (String) -> Unit, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(n) { first.requestWhenReady() }
    Column(
        Modifier.fillMaxHeight().width(620.dp).background(Color(0xF7141414))
            // While the reader is open, the remote stays inside it (Back closes it).
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup()
            .padding(36.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (n.image != null) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))) {
                SizedImage(n.image, 548.dp, 308.dp, Modifier.fillMaxSize())
            }
        }
        Text(meta(n) + (n.author?.let { "  ·  $it" } ?: ""), style = MaterialTheme.typography.labelMedium, color = Palette.TextMuted)
        Text(n.title, style = MaterialTheme.typography.headlineSmall, color = Color.White)
        n.summary?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            QrCode(n.link, 120.dp)
            Text("Scan to read the full story on your phone", style = MaterialTheme.typography.bodyMedium, color = Palette.TextSecondary)
        }
        val titles = n.mentionedTitles.take(3)
        if (titles.isEmpty()) {
            // Keep something focusable in the panel so the remote stays inside it.
            ActionButton("Close", null, onClose, Modifier.focusRequester(first))
        } else {
            Text("Mentioned in this story", style = MaterialTheme.typography.labelLarge, color = Palette.TextMuted)
            titles.forEachIndexed { i, t ->
                ActionButton("Find \"$t\" on 30nama", Icons.Default.Search, { onSearch(t) }, if (i == 0) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}
