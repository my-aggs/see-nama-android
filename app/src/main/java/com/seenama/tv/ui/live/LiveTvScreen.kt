package com.seenama.tv.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.seenama.tv.App
import com.seenama.tv.data.FarsiCatalog
import com.seenama.tv.data.LiveChannel
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.CenterLoading
import com.seenama.tv.ui.components.ErrorState
import com.seenama.tv.ui.components.SizedImage
import com.seenama.tv.ui.components.cardBorder
import com.seenama.tv.ui.components.cardColors
import com.seenama.tv.ui.components.rememberFocusMemory
import com.seenama.tv.ui.components.rememberInitialFocus
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

sealed interface LiveState {
    data object Loading : LiveState
    data object Error : LiveState
    data class Ready(val channels: List<LiveChannel>) : LiveState
}

class LiveTvViewModel(private val farsi: FarsiCatalog) : ViewModel() {
    private val _state = MutableStateFlow<LiveState>(LiveState.Loading)
    val state: StateFlow<LiveState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            if (_state.value !is LiveState.Ready) _state.value = LiveState.Loading
            runCatching { farsi.liveChannels() }
                .onSuccess { _state.value = if (it.isEmpty()) LiveState.Error else LiveState.Ready(it) }
                .onFailure { if (_state.value !is LiveState.Ready) _state.value = LiveState.Error }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { LiveTvViewModel((this[APPLICATION_KEY] as App).farsi) }
        }
    }
}

/** Persian live channels (news, entertainment, IRIB), from Farsihubz. */
@Composable
fun LiveTvScreen(nav: Nav) {
    val vm: LiveTvViewModel = viewModel(factory = LiveTvViewModel.Factory)
    val state by vm.state.collectAsStateWithLifecycle()
    when (val s = state) {
        LiveState.Loading -> CenterLoading()
        LiveState.Error -> ErrorState("Couldn't load live channels") { vm.load() }
        is LiveState.Ready -> ChannelGrid(s.channels) { nav.live(it.id) }
    }
}

@Composable
private fun ChannelGrid(channels: List<LiveChannel>, onOpen: (LiveChannel) -> Unit) {
    val memory = rememberFocusMemory()
    val first = rememberInitialFocus()
    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 60.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Palette.Red))
                    Spacer(Modifier.size(10.dp))
                    Text("Live TV", style = MaterialTheme.typography.displaySmall, color = Color.White)
                }
                Text(
                    "Persian channels, streaming live. In the player, press Up/Down to switch channels.",
                    style = MaterialTheme.typography.bodyLarge, color = Palette.TextSecondary, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        itemsIndexed(channels, key = { _, c -> c.id }) { i, c ->
            ChannelTile(c, { onOpen(c) }, memory.modifier("live:${c.id}").then(if (i == 0) Modifier.focusRequester(first) else Modifier))
        }
    }
}

@Composable
private fun ChannelTile(c: LiveChannel, onClick: () -> Unit, modifier: Modifier) {
    Column(modifier) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
            shape = CardDefaults.shape(RoundedCornerShape(8.dp)),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = cardBorder(),
            colors = cardColors(),
        ) {
            Box(Modifier.fillMaxSize()) {
                SizedImage(c.logo, 260.dp, 146.dp, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
                Row(
                    Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(3.dp)).background(Palette.Red).padding(horizontal = 6.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) { Text("LIVE", style = MaterialTheme.typography.labelSmall, color = Color.White) }
            }
        }
        Text(c.name, style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        c.about?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        Spacer(Modifier.height(2.dp))
    }
}
