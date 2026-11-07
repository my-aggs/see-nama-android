package com.seenama.tv.ui.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import androidx.compose.ui.focus.focusRequester
import com.seenama.tv.ui.components.requestWhenReady
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.data.SortOrder
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.PagedViewModel
import com.seenama.tv.ui.components.PosterGrid
import com.seenama.tv.ui.components.rememberFocusMemory
import com.seenama.tv.ui.theme.Palette

class ArchiveViewModel : PagedViewModel()

@Composable
fun ArchiveScreen(slug: String, name: String, nav: Nav) {
    val repo = LocalContext.current.app.repo
    val vm: ArchiveViewModel = viewModel()
    var sort by rememberSaveable { mutableStateOf(SortOrder.Update) }
    var loadedSort by rememberSaveable { mutableStateOf<SortOrder?>(null) }
    LaunchedEffect(sort) {
        if (loadedSort == sort && vm.state.value.items.isNotEmpty()) return@LaunchedEffect
        loadedSort = sort
        vm.reset { page -> repo.archive(slug, page, sort) }
    }
    val memory = rememberFocusMemory()
    val chipFocus = androidx.compose.runtime.remember { androidx.compose.ui.focus.FocusRequester() }
    val firstItem = androidx.compose.runtime.remember { com.seenama.tv.ui.components.TrackedFocus() }
    val state by vm.state.collectAsStateWithLifecycle()
    // Open on the first title (what you came for); the sort options are one press Up.
    LaunchedEffect(Unit) {
        if (memory.hasSaved) return@LaunchedEffect
        val loaded = kotlinx.coroutines.withTimeoutOrNull(5_000) { vm.state.first { it.items.isNotEmpty() || it.endReached || it.error } }
        if (loaded?.items?.isNotEmpty() == true && firstItem.request(2_000)) return@LaunchedEffect
        chipFocus.requestWhenReady()
    }
    Column(Modifier.fillMaxSize()) {
        PosterGrid(vm, onClick = { nav.details(it.id) }, columns = 7, memory = memory, firstItemFocus = firstItem, header = {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text(name, style = MaterialTheme.typography.displaySmall, color = Color.White)
                    // Farsihubz lists have a single (newest first) order.
                    if (!slug.startsWith("farsi:")) Row(
                        Modifier.padding(top = 16.dp, bottom = 8.dp).downTo(firstItem.requester, state.items.isNotEmpty()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SortOrder.entries.forEach { o ->
                            val m = memory.modifier("sort:${o.key}")
                            SortChip(o.label, o == sort, if (o == sort) m.focusRequester(chipFocus) else m) { sort = o }
                        }
                    }
                }
            }
        })
    }
}

/** Down from a filter row goes to the first title, not whichever column happens to be below. */
fun Modifier.downTo(target: androidx.compose.ui.focus.FocusRequester, enabled: Boolean): Modifier =
    if (!enabled) this else focusProperties { down = target }

@Composable
fun SortChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f),
            contentColor = if (selected) Color.White else Palette.TextSecondary,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
        ),
    ) { Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) }
}
