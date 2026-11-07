package com.seenama.tv.ui

import android.net.Uri
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.seenama.tv.player.PlayerScreen
import com.seenama.tv.ui.browse.ArchiveScreen
import com.seenama.tv.ui.details.DetailsScreen
import com.seenama.tv.ui.login.LoginScreen
import com.seenama.tv.ui.main.MainScreen
import com.seenama.tv.ui.splash.SplashScreen

object Routes {
    const val Splash = "splash"
    const val Login = "login"
    const val Main = "main"
    fun details(id: Long) = "details/$id"
    fun archive(slug: String, name: String) = "archive/${Uri.encode(slug)}/${Uri.encode(name)}"
    fun player(id: Long, free: Boolean, fileId: Long = -1) = "player/$id/$free?file=$fileId"
    fun live(channelId: Long) = "live/$channelId"
}

/** Navigation actions handed to screens so they never touch the controller directly. */
class Nav(private val c: NavHostController) {
    fun details(id: Long) = c.navigate(Routes.details(id)) { launchSingleTop = true }
    fun archive(slug: String, name: String) = c.navigate(Routes.archive(slug, name))
    fun player(id: Long, free: Boolean, fileId: Long = -1) = c.navigate(Routes.player(id, free, fileId)) { launchSingleTop = true }
    fun live(channelId: Long) = c.navigate(Routes.live(channelId)) { launchSingleTop = true }
    fun login() = c.navigate(Routes.Login)
    fun back() { c.popBackStack() }
    fun toMain() = c.navigate(Routes.Main) { popUpTo(0) { inclusive = true } }
    fun toLogin() = c.navigate(Routes.Login) { popUpTo(0) { inclusive = true } }
}

@Composable
fun AppNav() {
    val controller = rememberNavController()
    val nav = Nav(controller)
    val fade = tween<Float>(220)
    NavHost(
        navController = controller,
        startDestination = Routes.Splash,
        enterTransition = { fadeIn(fade) },
        exitTransition = { fadeOut(fade) },
        popEnterTransition = { fadeIn(fade) },
        popExitTransition = { fadeOut(fade) },
    ) {
        composable(Routes.Splash) { SplashScreen(nav) }
        composable(Routes.Login) { LoginScreen(nav) }
        composable(Routes.Main) { MainScreen(nav) }
        composable(
            "details/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
        ) { DetailsScreen(it.arguments!!.getLong("id"), nav) }
        composable(
            "archive/{slug}/{name}",
            arguments = listOf(navArgument("slug") { type = NavType.StringType }, navArgument("name") { type = NavType.StringType }),
        ) { ArchiveScreen(it.arguments!!.getString("slug")!!, it.arguments!!.getString("name")!!, nav) }
        composable(
            "live/{id}",
            arguments = listOf(navArgument("id") { type = NavType.LongType }),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) { com.seenama.tv.ui.live.LivePlayerScreen(it.arguments!!.getLong("id"), nav) }
        composable(
            "player/{id}/{free}?file={file}",
            arguments = listOf(
                navArgument("id") { type = NavType.LongType },
                navArgument("free") { type = NavType.BoolType },
                navArgument("file") { type = NavType.LongType; defaultValue = -1L },
            ),
            enterTransition = { EnterTransition.None },
            exitTransition = { ExitTransition.None },
        ) { PlayerScreen(it.arguments!!.getLong("id"), it.arguments!!.getBoolean("free"), it.arguments!!.getLong("file"), nav) }
    }
}
