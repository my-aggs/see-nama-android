package com.seenama.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

val NetflixEasing = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

/**
 * Keeps the focused child at a fixed fraction of the viewport instead of the
 * default "scroll just enough" behaviour. This is what makes rows and cards
 * feel anchored like Netflix: focus stays put, content slides under it.
 */
@OptIn(ExperimentalFoundationApi::class)
class PivotBringIntoViewSpec(
    private val parentFraction: Float,
    private val childFraction: Float = 0f,
    /** Horizontal lists in RTL report physical (left-edge) offsets; measure from the right instead. */
    private val mirrored: Boolean = false,
    /** Extra fixed offset from the start edge, e.g. the list's content padding. */
    private val startOffsetPx: Float = 0f,
) : BringIntoViewSpec {
    @Deprecated("", level = DeprecationLevel.HIDDEN)
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(220, easing = NetflixEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val target = parentFraction * containerSize - childFraction * size + startOffsetPx
        if (mirrored) {
            val fromStart = containerSize - offset - size
            return -(fromStart - target)
        }
        return offset - target
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PivotScroll(
    parentFraction: Float,
    horizontal: Boolean = false,
    startOffset: androidx.compose.ui.unit.Dp = androidx.compose.ui.unit.Dp(0f),
    content: @Composable () -> Unit,
) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    val offsetPx = with(androidx.compose.ui.platform.LocalDensity.current) { startOffset.toPx() }
    val spec = remember(parentFraction, rtl, horizontal, offsetPx) {
        PivotBringIntoViewSpec(parentFraction, mirrored = horizontal && rtl, startOffsetPx = offsetPx)
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) { content() }
}

/** Drops key repeats faster than [minIntervalMs] so held D-pad keys don't outrun rendering. */
fun Modifier.throttleKeyRepeat(minIntervalMs: Long = 70L): Modifier {
    var last = 0L
    return onPreviewKeyEvent { ev ->
        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val isDpad = ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight ||
            ev.key == Key.DirectionUp || ev.key == Key.DirectionDown
        if (!isDpad) return@onPreviewKeyEvent false
        val now = System.currentTimeMillis()
        if (now - last < minIntervalMs) true else { last = now; false }
    }
}

/**
 * Remembers which item had focus (by key) across navigation and puts focus
 * back on it when the screen is recreated.
 */
@androidx.compose.runtime.Stable
class FocusMemory internal constructor(private val saved: androidx.compose.runtime.MutableState<String?>) {
    val requester = androidx.compose.ui.focus.FocusRequester()
    internal val pendingKey: String? = saved.value
    val hasSaved: Boolean get() = pendingKey != null
    internal var restored = false

    // On-demand restore (e.g. coming back from the menu): only while restoring is a requester
    // attached to the last-focused element, so normal focus moves never recompose the cards.
    private var restoreKey by androidx.compose.runtime.mutableStateOf<String?>(null)
    private val restoreRequester = androidx.compose.ui.focus.FocusRequester()
    private var focusedKey: String? = null

    fun modifier(key: String): Modifier {
        val base = Modifier.onFocusChanged {
            if (it.isFocused) {
                saved.value = key
                focusedKey = key
                if (key == pendingKey) restored = true
            } else if (focusedKey == key) focusedKey = null
        }
        val pending = if (key == pendingKey) base.focusRequester(requester) else base
        return if (key == restoreKey) pending.focusRequester(restoreRequester) else pending
    }

    /** Puts focus back on the last-focused element of this screen. */
    suspend fun restoreLast(): Boolean {
        val key = saved.value ?: return false
        restoreKey = key
        val ok = restoreRequester.requestUntil(1_000) { focusedKey == key }
        restoreKey = null
        return ok
    }
}

/** Lets the shell (MainScreen) ask the current tab to restore its last focused element. */
class FocusRegistry { var active: FocusMemory? = null }

val LocalFocusRegistry = androidx.compose.runtime.staticCompositionLocalOf<FocusRegistry?> { null }

@Composable
fun rememberFocusMemory(): FocusMemory {
    val saved = androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val memory = remember { FocusMemory(saved) }
    val registry = LocalFocusRegistry.current
    androidx.compose.runtime.DisposableEffect(memory) {
        registry?.active = memory
        onDispose { if (registry?.active === memory) registry.active = null }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!memory.hasSaved) return@LaunchedEffect
        memory.requester.requestUntil(3000) { memory.restored }
    }
    return memory
}

/**
 * Requests focus, retrying while the target isn't attached yet (content that
 * loads asynchronously). Returns true once the request went through.
 */
suspend fun androidx.compose.ui.focus.FocusRequester.requestWhenReady(timeoutMs: Long = 2000): Boolean =
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        while (!runCatching { requestFocus() }.isSuccess) kotlinx.coroutines.delay(32)
        true
    } ?: false

/**
 * Lets a tab screen tell the shell which element should receive focus when
 * the user enters it (e.g. the billboard's Play button), instead of
 * whatever the geometric focus search happens to pick.
 */
class InitialFocus {
    var requester: androidx.compose.ui.focus.FocusRequester? = null
}

val LocalInitialFocus = androidx.compose.runtime.staticCompositionLocalOf { InitialFocus() }

/**
 * Like [requestWhenReady] but keeps re-requesting until [isFocused] confirms
 * focus actually landed. Needed right after navigation, when the outgoing
 * screen can clear focus after our request.
 */
suspend fun androidx.compose.ui.focus.FocusRequester.requestUntil(timeoutMs: Long = 2000, isFocused: () -> Boolean): Boolean =
    kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        while (!isFocused()) {
            runCatching { requestFocus() }
            kotlinx.coroutines.delay(48)
        }
        true
    } ?: false

/** Registers a requester as this tab screen's initial focus target (see [InitialFocus]). */
@Composable
fun rememberInitialFocus(): androidx.compose.ui.focus.FocusRequester {
    val fr = remember { androidx.compose.ui.focus.FocusRequester() }
    val initial = LocalInitialFocus.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        initial.requester = fr
        onDispose { if (initial.requester === fr) initial.requester = null }
    }
    return fr
}

/**
 * Vertical scrolling for detail-style pages: if the focused element is already fully on screen,
 * don't scroll at all (Android TV's default pivots it to ~30%, which pushed the title off-screen
 * while moving between buttons); otherwise bring it to [fraction] of the viewport.
 */
@OptIn(ExperimentalFoundationApi::class)
class VisibleOrPivotSpec(private val fraction: Float) : BringIntoViewSpec {
    @Deprecated("", level = DeprecationLevel.HIDDEN)
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(220, easing = NetflixEasing)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (offset >= 0f && offset + size <= containerSize) return 0f
        return offset - fraction * containerSize
    }
}

/**
 * A focus target that knows whether it actually has focus. `requestFocus()` can silently do
 * nothing for an element that's still animating in, so callers retry until focus is confirmed.
 */
@androidx.compose.runtime.Stable
class TrackedFocus {
    val requester = androidx.compose.ui.focus.FocusRequester()
    var isFocused by androidx.compose.runtime.mutableStateOf(false)
        internal set

    suspend fun request(timeoutMs: Long = 1_500): Boolean = requester.requestUntil(timeoutMs) { isFocused }
}

fun Modifier.trackFocus(t: TrackedFocus): Modifier =
    focusRequester(t.requester).onFocusChanged { t.isFocused = it.isFocused }
