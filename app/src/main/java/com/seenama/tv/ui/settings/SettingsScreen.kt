package com.seenama.tv.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import com.seenama.tv.BuildConfig
import com.seenama.tv.app
import com.seenama.tv.data.AppJson
import com.seenama.tv.data.ServerHealth
import com.seenama.tv.data.path
import com.seenama.tv.data.str
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.ActionButton
import com.seenama.tv.ui.components.SizedImage
import com.seenama.tv.ui.components.rememberInitialFocus
import com.seenama.tv.ui.theme.Palette
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

private val badges = mapOf(
    "golden" to ("Gold" to Color(0xFFF7AB1B)),
    "red" to ("Red" to Color(0xFFD34040)),
    "free" to ("Free" to Color(0xFFFFFFFF)),
    "blue" to ("Blue" to Color(0xFF4A71B7)),
    "staff" to ("Staff" to Color(0xFF6DBE45)),
    "admin" to ("Admin" to Color(0xFF68CCE3)),
    "robot" to ("Robot" to Color(0xFF6FC281)),
)

private val subtitleOptions = listOf("fa" to "Persian", "en" to "English", "hybrid" to "Persian + English", "none" to "Off")
private val qualityOptions = listOf("Auto", "1080p", "720p", "480p")
private val sizeOptions = listOf(24 to "Small", 32 to "Medium", 40 to "Large", 48 to "Extra large")

@Composable
fun SettingsScreen(nav: Nav) {
    val app = LocalContext.current.app
    val prefs = app.prefs
    val user by app.repo.user.collectAsStateWithLifecycle()
    val connection by app.connection.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val first = rememberInitialFocus()
    // Bumped after each change so rows re-read SharedPreferences.
    var version by remember { mutableIntStateOf(0) }
    var mirrors by remember { mutableStateOf<List<Pair<String, Long?>>?>(null) }
    var testing by remember { mutableStateOf(false) }
    var cacheCleared by remember { mutableStateOf(false) }
    val days = prefs.userJson?.let { runCatching { AppJson.parseToJsonElement(it).path("user_expire.days").str() }.getOrNull() }

    fun <T> cycle(options: List<T>, current: T): T = options[(options.indexOf(current) + 1) % options.size]

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 48.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item { Text("Settings", style = MaterialTheme.typography.displaySmall, color = Color.White, modifier = Modifier.padding(bottom = 20.dp)) }

        // ---- Account ----
        item {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Surface).padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Red), contentAlignment = Alignment.Center) {
                    Text((user?.name ?: "G").take(1).uppercase(), style = MaterialTheme.typography.displaySmall, color = Color.White)
                    user?.avatar?.let { SizedImage(it, 72.dp, 72.dp, Modifier.fillMaxSize()) }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(user?.name ?: "Guest", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                    val badge = badges[user?.type]
                    if (badge != null) Text("${badge.first} plan" + (days?.let { " · $it days remaining" } ?: ""), style = MaterialTheme.typography.titleSmall, color = badge.second)
                    if (user != null && !user!!.allowedToStream) Text("Your plan doesn't include streaming.", style = MaterialTheme.typography.bodyMedium, color = Palette.Red)
                }
                if (app.repo.isLoggedIn) {
                    ActionButton("Sign out", Icons.AutoMirrored.Filled.Logout, { app.repo.logout(); prefs.guest = false; nav.toLogin() })
                } else {
                    ActionButton("Sign in", Icons.AutoMirrored.Filled.Login, { nav.login() })
                }
            }
        }

        // ---- Playback ----
        item { Section("Playback") }
        item(key = "autoplay") {
            version // re-read prefs after a change without recreating (and unfocusing) the row
            SettingRow("Autoplay next episode", if (prefs.autoplayNext) "On" else "Off", modifier = Modifier.focusRequester(first)) { prefs.autoplayNext = !prefs.autoplayNext; version++ }
        }
        item(key = "skip") {
            version // re-read prefs after a change without recreating (and unfocusing) the row
            SettingRow("Skip intros and recaps automatically", if (prefs.autoSkipIntro) "On" else "Off") { prefs.autoSkipIntro = !prefs.autoSkipIntro; version++ }
        }
        item(key = "subs") {
            version // re-read prefs after a change without recreating (and unfocusing) the row
            val current = subtitleOptions.firstOrNull { it.first == prefs.subtitleLanguage } ?: subtitleOptions.first()
            SettingRow("Default subtitles", current.second) { prefs.subtitleLanguage = cycle(subtitleOptions, current).first; version++ }
        }
        item(key = "subsize") {
            version // re-read prefs after a change without recreating (and unfocusing) the row
            val current = sizeOptions.minByOrNull { kotlin.math.abs(it.first - prefs.subtitleSize) } ?: sizeOptions[1]
            SettingRow("Subtitle size", current.second) { prefs.subtitleSize = cycle(sizeOptions, current).first; version++ }
        }
        item(key = "quality") {
            version // re-read prefs after a change without recreating (and unfocusing) the row
            val current = qualityOptions.firstOrNull { it.equals(prefs.quality, true) } ?: "Auto"
            SettingRow("Preferred video quality", current) { prefs.quality = cycle(qualityOptions, current); prefs.qualityChosen = true; version++ }
        }

        // ---- Server ----
        item { Section("Server") }
        item {
            val (dot, label) = when (connection.health) {
                ServerHealth.Online -> Palette.Match to "Connected"
                ServerHealth.Degraded -> Color(0xFFF5A524) to "Reconnecting"
                ServerHealth.Offline -> Palette.Red to if (!connection.network) "No internet" else "Unreachable"
            }
            SettingRow(
                "Status",
                "$label · ${connection.serverName}" + (connection.latencyMs?.let { " · $it ms" } ?: ""),
                dot = dot,
            ) {
                if (testing) return@SettingRow
                testing = true
                scope.launch {
                    val hosts = app.api.mirrors(refresh = true)
                    mirrors = coroutineScope { hosts.map { h -> async { h to app.api.probe(h) } }.awaitAll() }
                        .sortedBy { it.second ?: Long.MAX_VALUE }
                    testing = false
                }
            }
        }
        item {
            Text(
                if (testing) "Testing servers…" else if (mirrors == null) "Select Status to test all servers and pick the fastest." else "Select a server to switch to it.",
                style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
            )
        }
        mirrors?.forEach { (host, ms) ->
            item(key = "mirror-$host") {
            version
                val name = host.substringAfter("://").substringBefore('/')
                val active = host == app.api.endpoint
                SettingRow(
                    (if (active) "✓  " else "    ") + name,
                    ms?.let { "$it ms" } ?: "No response",
                    dot = when { ms == null -> Palette.Red; ms < 800 -> Palette.Match; else -> Color(0xFFF5A524) },
                ) {
                    if (ms != null) { app.api.switchTo(host); app.repo.refreshAllInBackground(); version++ }
                }
            }
        }

        // ---- Storage ----
        item { Section("Storage") }
        item {
            SettingRow("Clear cached images and data", if (cacheCleared) "Cleared" else "") {
                scope.launch {
                    SingletonImageLoader.get(app).let { it.memoryCache?.clear(); it.diskCache?.clear() }
                    app.apiCache.clear()
                    cacheCleared = true
                }
            }
        }
        item {
            Spacer(Modifier.height(24.dp))
            Text("See-Nama ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = Palette.TextMuted)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = Palette.TextMuted, modifier = Modifier.padding(top = 24.dp, bottom = 6.dp))
}

@Composable
private fun SettingRow(label: String, value: String, dot: Color? = null, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Palette.Surface, contentColor = Color.White,
            focusedContainerColor = Color.White, focusedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (dot != null) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(10.dp))
            }
            Text(value, style = MaterialTheme.typography.titleSmall)
        }
    }
}
