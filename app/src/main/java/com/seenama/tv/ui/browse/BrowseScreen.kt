package com.seenama.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.data.Slug
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.CenterLoading
import com.seenama.tv.ui.components.PivotScroll
import com.seenama.tv.ui.components.SlugCard
import com.seenama.tv.ui.components.rememberFocusMemory

@Composable
fun BrowseScreen(nav: Nav) {
    val repo = LocalContext.current.app.repo
    val menu by repo.menu.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (repo.menu.value == null) runCatching { repo.loadMenu() } }
    val m = menu ?: return CenterLoading()
    val memory = rememberFocusMemory()
    val first = com.seenama.tv.ui.components.rememberInitialFocus()
    val sections = listOf("Categories" to m.first, "Genres" to m.second).filter { it.second.isNotEmpty() }
    PivotScroll(parentFraction = 0.25f) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 48.dp, bottom = 200.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            items(sections, key = { it.first }) { (label, slugs) ->
                SlugRow(label, slugs, memory, if (label == sections.first().first) first else null) { nav.archive(it.slug, it.name) }
            }
        }
    }
}

@Composable
private fun SlugRow(
    label: String,
    slugs: List<Slug>,
    memory: com.seenama.tv.ui.components.FocusMemory,
    firstFocus: androidx.compose.ui.focus.FocusRequester?,
    onClick: (Slug) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.headlineSmall, color = Color.White, modifier = Modifier.padding(start = 48.dp, bottom = 12.dp))
        PivotScroll(parentFraction = 0f, horizontal = true, startOffset = 48.dp) {
            LazyRow(contentPadding = PaddingValues(horizontal = 48.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(slugs, key = { it.slug }) { s ->
                    val m = memory.modifier("slug:${s.slug}")
                    SlugCard(s, { onClick(s) }, if (firstFocus != null && s == slugs.first()) m.focusRequester(firstFocus) else m)
                }
            }
        }
    }
}
