package com.seenama.tv.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.History
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.SpaceBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.FocusMemory
import com.seenama.tv.ui.components.PagedViewModel
import com.seenama.tv.ui.components.PosterGrid
import com.seenama.tv.ui.components.rememberFocusMemory
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.async
import kotlinx.coroutines.delay

class SearchViewModel : PagedViewModel()

private val English = ('a'..'z').map { it.toString() }
private val Digits = (1..9).map { it.toString() } + "0"

@Composable
fun SearchScreen(nav: Nav, initialQuery: String? = null) {
    val repo = LocalContext.current.app.repo
    val vm: SearchViewModel = viewModel()
    val prefs = LocalContext.current.app.prefs
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(initialQuery) { if (!initialQuery.isNullOrBlank()) query = initialQuery }
    var recent by remember { mutableStateOf(prefs.recentSearches) }
    // Remember a query once the user actually opens something from its results.
    val openTitle: (com.seenama.tv.data.Title) -> Unit = { t ->
        val q = query.trim()
        if (q.length >= 3) { prefs.recentSearches = (listOf(q) + prefs.recentSearches.filterNot { it.equals(q, true) }); recent = prefs.recentSearches }
        nav.details(t.id)
    }
    var searched by rememberSaveable { mutableStateOf("") }
    val memory = rememberFocusMemory()
    val firstKey = com.seenama.tv.ui.components.rememberInitialFocus()

    // Debounced as-you-type search, like Netflix.
    LaunchedEffect(query) {
        val q = query.trim()
        if (q == searched) return@LaunchedEffect
        if (q.length < 3) { vm.clear(); searched = q; return@LaunchedEffect }
        delay(400)
        searched = q
        // 30nama and Farsihubz are searched together; Farsihubz matches join the first page.
        vm.reset { page ->
            if (page > 1) repo.search(q, page)
            else kotlinx.coroutines.coroutineScope {
                val farsi = async { runCatching { repo.farsiSearch(q) }.getOrDefault(emptyList()) }
                val nama = runCatching { repo.search(q, 1) }
                val f = farsi.await()
                nama.fold(
                    { p -> p.copy(items = (p.items + f).distinctBy { it.id }) },
                    { if (f.isNotEmpty()) com.seenama.tv.data.Page(f, 1, 1) else throw it },
                )
            }
        }
    }

    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(400.dp).fillMaxHeight().padding(start = 40.dp, top = 32.dp, end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(6.dp)).background(Palette.SurfaceHigh).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Search, null, tint = Palette.TextSecondary)
                Text(
                    query.ifEmpty { "Titles, people, genres" },
                    style = MaterialTheme.typography.titleMedium,
                    color = if (query.isEmpty()) Palette.TextMuted else Color.White,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Keyboard(
                letters = English,
                memory = memory,
                firstKey = firstKey,
                onKey = { query += it },
                onSpace = { if (query.isNotEmpty() && !query.endsWith(" ")) query += " " },
                onDelete = { query = query.dropLast(1) },
                onClear = { query = "" },
            )
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (searched.length >= 3) {
                PosterGrid(vm, onClick = openTitle, columns = 4, memory = memory, emptyText = "No results for \"$searched\"")
            } else if (recent.isNotEmpty() && query.isBlank()) {
                Column(Modifier.padding(start = 48.dp, top = 40.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Recent searches", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    recent.forEach { r ->
                        WideKey(Icons.Default.History, r, Modifier.width(420.dp)) { query = r }
                    }
                    Spacer(Modifier.height(8.dp))
                    WideKey(null, "Clear recent searches", Modifier.width(420.dp)) { prefs.recentSearches = emptyList(); recent = emptyList() }
                }
            } else {
                Text(
                    "Type at least 3 characters",
                    style = MaterialTheme.typography.titleLarge,
                    color = Palette.TextMuted,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }
    }
}

@Composable
private fun Keyboard(
    letters: List<String>,
    memory: FocusMemory,
    firstKey: androidx.compose.ui.focus.FocusRequester,
    onKey: (String) -> Unit,
    onSpace: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    val cols = 7
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WideKey(Icons.Default.SpaceBar, "Space", memory.modifier("k:space").weight(1f), onSpace)
            WideKey(Icons.AutoMirrored.Filled.Backspace, "Delete", memory.modifier("k:del").weight(1f), onDelete)
        }
        (letters + Digits).chunked(cols).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    val m = memory.modifier("k:$k").weight(1f)
                    Key(k, if (k == letters.first()) m.focusRequester(firstKey) else m) { onKey(k) }
                }
                repeat(cols - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WideKey(null, "Clear", memory.modifier("k:clear").weight(1f), onClear)
        }
    }
}

@Composable
private fun keyColors() = ClickableSurfaceDefaults.colors(
    containerColor = Color.White.copy(alpha = 0.06f),
    contentColor = Color.White,
    focusedContainerColor = Color.White,
    focusedContentColor = Color.Black,
)

@Composable
private fun Key(label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = keyColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun WideKey(icon: ImageVector?, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp)),
        colors = keyColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp).padding(end = 4.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
