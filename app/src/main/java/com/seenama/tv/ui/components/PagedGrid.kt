package com.seenama.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.data.Page
import com.seenama.tv.data.Title
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class GridState(
    val items: List<Title> = emptyList(),
    val loading: Boolean = false,
    val error: Boolean = false,
    val endReached: Boolean = false,
)

/** Paginated title list; call [reset] whenever the query/sort changes. */
open class PagedViewModel : ViewModel() {
    private val _state = MutableStateFlow(GridState())
    val state: StateFlow<GridState> = _state
    private var loader: (suspend (Int) -> Page<Title>)? = null
    private var page = 0
    private var job: Job? = null

    fun reset(newLoader: suspend (Int) -> Page<Title>) {
        job?.cancel()
        loader = newLoader
        page = 0
        _state.value = GridState(loading = true)
        loadMore()
    }

    fun clear() {
        job?.cancel()
        loader = null
        _state.value = GridState()
    }

    fun loadMore() {
        val l = loader ?: return
        val s = _state.value
        if (s.endReached || (job?.isActive == true)) return
        _state.value = s.copy(loading = true, error = false)
        job = viewModelScope.launch {
            runCatching { l(page + 1) }
                .onSuccess { p ->
                    page = p.page
                    val merged = (_state.value.items + p.items).distinctBy { it.id }
                    _state.value = GridState(merged, loading = false, endReached = p.isLast)
                }
                .onFailure { _state.value = _state.value.copy(loading = false, error = true) }
        }
    }
}

/**
 * Poster grid with infinite scroll. [header] lets screens put filters above
 * the grid inside the same scroll container so focus moves naturally.
 */
@Composable
fun PosterGrid(
    vm: PagedViewModel,
    onClick: (Title) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 6,
    memory: FocusMemory? = null,
    emptyText: String = "Nothing here yet",
    /** Attached to the first title, so screens can focus it or route "Down" from their filters to it. */
    firstItemFocus: TrackedFocus? = null,
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.foundation.layout.BoxWithConstraints(modifier) {
        // Actual grid width minus side padding (96) and gaps.
        val cellWidth = (maxWidth - 96.dp - 16.dp * (columns - 1)) / columns
        PivotScroll(parentFraction = 0.3f) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().throttleKeyRepeat(),
                contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 24.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                header?.invoke(this)
                itemsIndexed(state.items, key = { _, t -> t.id }, contentType = { _, _ -> "poster" }) { i, t ->
                    androidx.compose.foundation.layout.Column {
                        PosterCard(
                            t,
                            onClick = { onClick(t) },
                            modifier = (memory?.modifier("grid:${t.id}") ?: Modifier)
                                .then(if (i == 0 && firstItemFocus != null) Modifier.trackFocus(firstItemFocus) else Modifier)
                                .onFocusChanged {
                                // Prefetch the next page two rows before the end.
                                if (it.isFocused && i >= state.items.size - columns * 2) vm.loadMore()
                            },
                            width = cellWidth,
                        )
                        Text(
                            t.displayTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = androidx.compose.ui.graphics.Color.White,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp).width(cellWidth),
                        )
                        val sub = listOfNotNull(t.year, t.seasons?.takeIf { it > 0 }?.let { "$it Season${if (it == 1) "" else "s"}" })
                        if (sub.isNotEmpty()) {
                            Text(sub.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1)
                        }
                    }
                }
                if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.padding(24.dp), contentAlignment = Alignment.Center) { LoadingDots() }
                } else if (state.items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (state.error) "Couldn't load titles" else emptyText,
                        style = MaterialTheme.typography.titleMedium,
                        color = Palette.TextSecondary,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
        }
    }
}
