package com.seenama.tv.ui.mylist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.app
import com.seenama.tv.data.ListGroup
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.browse.SortChip
import com.seenama.tv.ui.browse.downTo
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.PagedViewModel
import com.seenama.tv.ui.components.PosterGrid
import com.seenama.tv.ui.components.rememberFocusMemory
import com.seenama.tv.ui.theme.Palette

class MyListViewModel : PagedViewModel()

@Composable
fun MyListScreen(nav: Nav) {
    val repo = LocalContext.current.app.repo
    if (!repo.isLoggedIn) {
        Column(Modifier.fillMaxSize().padding(64.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("My List", style = MaterialTheme.typography.displaySmall, color = Color.White)
            Text("Sign in to build your personal list.", style = MaterialTheme.typography.titleMedium, color = Palette.TextSecondary)
            ActionButton("Sign in", null, { nav.login() })
        }
        return
    }
    val vm: MyListViewModel = viewModel()
    var group by rememberSaveable { mutableStateOf(ListGroup.Watchlist) }
    // Reload whenever the library changes (titles added/removed from the details page).
    val library by repo.library.collectAsStateWithLifecycle()
    LaunchedEffect(group, library) { vm.reset { page -> repo.userList(group, page) } }
    val memory = rememberFocusMemory()
    val chipFocus = com.seenama.tv.ui.components.rememberInitialFocus()
    val firstItem = androidx.compose.runtime.remember { com.seenama.tv.ui.components.TrackedFocus() }
    val gridState by vm.state.collectAsStateWithLifecycle()
    PosterGrid(vm, onClick = { nav.details(it.id) }, columns = 6, memory = memory, emptyText = "This list is empty", firstItemFocus = firstItem, header = {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("My List", style = MaterialTheme.typography.displaySmall, color = Color.White)
                Row(
                    Modifier.padding(top = 16.dp, bottom = 8.dp).downTo(firstItem.requester, gridState.items.isNotEmpty()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ListGroup.entries.forEach { g ->
                        val m = memory.modifier("group:${g.key}")
                        SortChip(g.label, g == group, if (g == group) m.focusRequester(chipFocus) else m) { group = g }
                    }
                }
            }
        }
    })
}
