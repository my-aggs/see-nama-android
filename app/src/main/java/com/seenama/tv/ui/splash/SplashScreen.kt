package com.seenama.tv.ui.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.seenama.tv.app
import com.seenama.tv.ui.Nav
import com.seenama.tv.ui.components.ErrorState
import com.seenama.tv.ui.components.LoadingDots
import com.seenama.tv.ui.components.NetflixEasing
import com.seenama.tv.ui.components.Wordmark

@Composable
fun SplashScreen(nav: Nav) {
    val app = LocalContext.current.app
    val repo = app.repo
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableIntStateOf(0) }
    val scale = remember { Animatable(0.85f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(500))
    }
    LaunchedEffect(Unit) { scale.animateTo(1f, tween(900, easing = NetflixEasing)) }
    LaunchedEffect(attempt) {
        failed = false
        val signedInOrGuest = repo.isLoggedIn || app.prefs.guest
        // Warm start: show the last good home instantly and refresh it in the background.
        if (attempt == 0 && signedInOrGuest && repo.restoreFromDisk()) {
            repo.refreshAllInBackground()
            nav.toMain()
            return@LaunchedEffect
        }
        val ok = runCatching { repo.bootstrap() }.isSuccess
        if (!ok) { failed = true; return@LaunchedEffect }
        if (signedInOrGuest) nav.toMain() else nav.toLogin()
    }

    if (failed) {
        ErrorState("Can't reach the server", autoRetry = true) { attempt++ }
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Wordmark(72, Modifier.scale(scale.value).alpha(alpha.value))
            Spacer(Modifier.height(32.dp))
            LoadingDots(Modifier.alpha(alpha.value))
        }
    }
}
