package com.seenama.tv.ui.main

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tv
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.focusGroup
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seenama.tv.app
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.browse.BrowseScreen
import com.seenama.tv.ui.home.HomeFilter
import com.seenama.tv.ui.home.HomeScreen
import com.seenama.tv.ui.mylist.MyListScreen
import com.seenama.tv.ui.search.SearchScreen
import com.seenama.tv.ui.settings.SettingsScreen
import com.seenama.tv.ui.theme.Palette
import com.seenama.tv.ui.components.requestUntil
import com.seenama.tv.ui.components.InitialFocus
import com.seenama.tv.ui.components.LocalInitialFocus
import com.seenama.tv.data.ConnectionState
import com.seenama.tv.data.ServerHealth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Newspaper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Tracks Left presses at the content's left edge (plain fields: no recomposition needed). */
private class EdgeLeft { var at = 0L; var repeat = false }

enum class Tab(val label: String, val icon: ImageVector) {
    Search("Search", Icons.Default.Search),
    Home("Home", Icons.Default.Home),
    Series("TV Shows", Icons.Default.Tv),
    Movies("Movies", Icons.Default.Movie),
    Farsi("Farsi", Icons.Default.Language),
    Live("Live TV", Icons.Default.LiveTv),
    Browse("Categories", Icons.Default.Category),
    MyList("My List", Icons.Default.Add),
    News("News", Icons.Default.Newspaper),
    Notifications("Notifications", Icons.Default.Notifications),
    Settings("Settings", Icons.Default.Settings),
}

private val RailCollapsed = 76.dp
private val RailExpanded = 250.dp

@Composable
fun MainScreen(nav: Nav) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var railFocused by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }
    val edgeLeft = remember { EdgeLeft() }
    val focusRegistry = remember { com.seenama.tv.ui.components.FocusRegistry() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val contentFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    var focusContentTick by rememberSaveable { mutableStateOf(1) } // >0 → move focus into content once it's ready
    val stateHolder = rememberSaveableStateHolder()
    val initialFocus = remember { InitialFocus() }
    var lastBack by remember { mutableStateOf(0L) }
    /** Up/Down presses in the menu: a sign the user is going somewhere else. */
    var railMoves by remember { mutableStateOf(0) }
    /** Query handed to Search from elsewhere (e.g. "Find on 30nama" in News). */
    var pendingSearch by remember { mutableStateOf<String?>(null) }

    // Back: from content → rail; from rail on another tab → Home; on Home rail → press twice to exit.
    BackHandler {
        when {
            !railFocused -> runCatching { railFocus.requestFocus() }
            tab != Tab.Home -> { tab = Tab.Home; focusContentTick++ }
            System.currentTimeMillis() - lastBack < 2500 -> (ctx as? android.app.Activity)?.finish()
            else -> {
                lastBack = System.currentTimeMillis()
                Toast.makeText(ctx, "Press back again to exit", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(focusContentTick, tab) {
        if (focusContentTick == 0) return@LaunchedEffect
        // Content loads asynchronously; keep trying until something focusable exists.
        // Prefer the screen's own target (e.g. the billboard's Play); fall back to the container quickly.
        val preferred = withTimeoutOrNull(600) { while (initialFocus.requester == null) delay(16); initialFocus.requester }
        var ok = preferred != null && preferred.requestUntil(700) { contentHasFocus }
        if (!ok) ok = contentFocus.requestUntil(2500) { contentHasFocus }
        if (!ok) {
            // A slow tab (e.g. Farsi on a slow connection): move in as soon as it has something to focus,
            // unless the user has started moving around the menu in the meantime.
            val moves = railMoves
            withTimeoutOrNull(15_000) {
                while (!contentHasFocus && railMoves == moves) {
                    val target = initialFocus.requester
                    if (target != null && target.requestUntil(300) { contentHasFocus }) break
                    if (contentFocus.requestUntil(300) { contentHasFocus }) break
                    delay(300)
                }
            }
        }
        focusContentTick = 0
    }

    val dim by animateFloatAsState(if (railFocused) 1f else 0f, tween(250), label = "dim")
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().padding(start = RailCollapsed)
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.DirectionLeft) {
                        edgeLeft.repeat = ev.nativeKeyEvent.repeatCount > 0
                    }
                    false
                }
                // At the left edge, the first Left press does nothing; a second one within 0.8 s opens
                // the menu. Prevents opening it by accident while scrolling back through a row.
                .focusProperties {
                    onExit = {
                        if (requestedFocusDirection == FocusDirection.Left) {
                            // Only a fresh press counts as the second one (holding Left to scroll back never opens it).
                            val now = System.currentTimeMillis()
                            if (edgeLeft.repeat || now - edgeLeft.at > 800) { edgeLeft.at = now; cancelFocusChange() } else edgeLeft.at = 0L
                        }
                    }
                }
                .focusGroup()
        ) {
          // Separate layer: remembers the focused element when you leave for the menu and restores it on return
          // (kept apart from the edge-Left rule above, whose exit hook would otherwise override this one).
          Box(Modifier.fillMaxSize().focusRequester(contentFocus).focusRestorer().focusGroup()) {
            stateHolder.SaveableStateProvider(tab.name) {
              androidx.compose.runtime.CompositionLocalProvider(
                  LocalInitialFocus provides initialFocus,
                  com.seenama.tv.ui.components.LocalFocusRegistry provides focusRegistry,
              ) {
                when (tab) {
                    Tab.Home -> HomeScreen(nav, HomeFilter.All)
                    Tab.Series -> HomeScreen(nav, HomeFilter.Series)
                    Tab.Movies -> HomeScreen(nav, HomeFilter.Movies)
                    Tab.Farsi -> HomeScreen(nav, HomeFilter.Farsi)
                    Tab.Live -> com.seenama.tv.ui.live.LiveTvScreen(nav)
                    Tab.Search -> SearchScreen(nav, pendingSearch)
                    Tab.Browse -> BrowseScreen(nav)
                    Tab.MyList -> MyListScreen(nav)
                    Tab.News -> com.seenama.tv.ui.news.NewsScreen(nav) { q -> pendingSearch = q; tab = Tab.Search; focusContentTick++ }
                    Tab.Notifications -> com.seenama.tv.ui.notifications.NotificationsScreen(nav)
                    Tab.Settings -> SettingsScreen(nav)
                }
              }
            }
          }
        }

        // Netflix dims the page behind the expanded menu.
        if (dim > 0f) {
            Box(
                Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(
                    Brush.horizontalGradient(
                        0f to Color.Black.copy(alpha = 0.95f), 0.45f to Color.Black.copy(alpha = 0.6f), 1f to Color.Transparent,
                    )
                )
            )
        }
        NavRail(
            selected = tab,
            expanded = railFocused,
            modifier = Modifier.focusRequester(railFocus).onFocusChanged { railFocused = it.hasFocus }
                .onPreviewKeyEvent { ev ->
                    if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                        (ev.key == androidx.compose.ui.input.key.Key.DirectionUp || ev.key == androidx.compose.ui.input.key.Key.DirectionDown)
                    ) railMoves++
                    false
                },
            onSelect = { t -> if (t != tab) initialFocus.requester = null; tab = t; focusContentTick++ },
            // Programmatic entry goes through the content's focus restorer (directional search would skip it).
            onReturn = {
                scope.launch {
                    val restored = focusRegistry.active?.restoreLast() ?: false
                    if (!restored && !runCatching { contentFocus.requestFocus() }.isSuccess) focusContentTick++
                }
            },
        )
    }
}

@Composable
private fun NavRail(selected: Tab, expanded: Boolean, modifier: Modifier, onSelect: (Tab) -> Unit, onReturn: () -> Unit) {
    val width by animateDpAsState(if (expanded) RailExpanded else RailCollapsed, tween(220), label = "rail")
    val app = LocalContext.current.app
    val user by app.repo.user.collectAsStateWithLifecycle()
    val connection by app.connection.state.collectAsStateWithLifecycle()
    val notes by app.repo.notifications.collectAsStateWithLifecycle()
    val health = connection.health
    val selectedFr = remember { FocusRequester() }
    Column(
        modifier.width(width).fillMaxHeight().padding(vertical = 16.dp)
            // Entering the menu (Left from content, or Back) always lands on the current tab.
            .focusProperties {
                @Suppress("DEPRECATION")
                enter = { selectedFr }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.Center,
    ) {
        // Profile tile = account & settings (as on Netflix), with the server status dot.
        Surface(
            onClick = { onSelect(Tab.Settings) },
            modifier = Modifier
                .onPreviewKeyEvent { ev ->
                    if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.DirectionRight) {
                        if (selected == Tab.Settings) onReturn() else onSelect(Tab.Settings)
                        true
                    } else false
                }
                .padding(horizontal = 10.dp).padding(bottom = 10.dp).fillMaxWidth()
                .then(if (selected == Tab.Settings) Modifier.focusRequester(selectedFr) else Modifier),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent, contentColor = Color.White,
                focusedContainerColor = Color.White.copy(alpha = 0.14f), focusedContentColor = Color.White,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        ) {
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)).background(Palette.Red),
                    contentAlignment = Alignment.Center,
                ) { Text((user?.name ?: "G").take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium) }
                // Server status: green = connected, amber = slow/recovering, red = offline.
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp).size(13.dp).clip(CircleShape)
                        .background(Palette.Background).padding(2.dp).clip(CircleShape).background(health.color())
                )
            }
            AnimatedVisibility(expanded, enter = fadeIn(tween(200)), exit = fadeOut(tween(100))) {
                Column(Modifier.padding(start = 14.dp)) {
                    Text(user?.name ?: "Guest", style = MaterialTheme.typography.titleMedium, color = Color.White, maxLines = 1)
                    Text(connection.statusLine(), style = MaterialTheme.typography.labelSmall, color = health.color(), maxLines = 1)
                }
            }
        }
        }
        Tab.entries.filter { it != Tab.Settings }.forEach { t ->
            RailItem(
                t, t == selected, expanded, { onSelect(t) }, onReturn,
                badge = if (t == Tab.Notifications) notes.unread else 0,
                modifier = if (t == selected) Modifier.focusRequester(selectedFr) else Modifier,
            )
        }
    }
}

@Composable
private fun RailItem(tab: Tab, selected: Boolean, expanded: Boolean, onClick: () -> Unit, onReturn: () -> Unit, badge: Int = 0, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier
            .onPreviewKeyEvent { ev ->
                // Right on a highlighted tab opens it; on the current tab it returns to exactly where you were.
                if (ev.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && ev.key == androidx.compose.ui.input.key.Key.DirectionRight) {
                    if (selected) onReturn() else onClick()
                    true
                } else false
            }
            .padding(horizontal = 10.dp, vertical = 1.dp).fillMaxWidth().height(40.dp)
            .onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.White.copy(alpha = 0.14f),
            contentColor = if (selected) Color.White else Palette.TextMuted,
            focusedContentColor = Color.White,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Icon(tab.icon, contentDescription = tab.label, modifier = Modifier.size(24.dp))
                if (badge > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp).height(16.dp).widthIn(min = 16.dp)
                            .clip(RoundedCornerShape(8.dp)).background(Palette.Red).padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (badge > 9) "9+" else "$badge", style = MaterialTheme.typography.labelSmall, color = Color.White) }
                }
            }
            if (expanded) {
                Text(
                    tab.label,
                    style = if (selected) MaterialTheme.typography.titleMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    else MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 18.dp),
                    maxLines = 1,
                )
            }
        }
        if (selected && !expanded) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 5.dp).width(24.dp).height(3.dp).background(Palette.Red))
        }
    }
}

@Composable
private fun ServerHealth.color(): Color = when (this) {
    ServerHealth.Online -> Palette.Match
    ServerHealth.Degraded -> Color(0xFFF5A524)
    ServerHealth.Offline -> Palette.Red
}

private fun ConnectionState.statusLine(): String = when (health) {
    ServerHealth.Offline -> if (!network) "Offline" else "Server unreachable"
    ServerHealth.Degraded -> "Reconnecting · $serverName"
    ServerHealth.Online -> "Connected · $serverName" + (latencyMs?.let { " · $it ms" } ?: "")
}
