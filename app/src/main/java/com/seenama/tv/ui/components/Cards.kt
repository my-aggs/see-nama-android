package com.seenama.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.offset
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.seenama.tv.data.Slug
import com.seenama.tv.data.Title
import com.seenama.tv.ui.theme.Palette

private val CardShape = RoundedCornerShape(6.dp)

/** Loads an image decoded at exactly the on-screen size — the biggest single win for scroll smoothness. */
@Composable
fun SizedImage(
    url: String?,
    width: Dp,
    height: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    crossfadeMs: Int = 0,
) {
    val ctx = LocalContext.current
    val px = with(LocalDensity.current) { width.roundToPx() to height.roundToPx() }
    // A new request after reconnecting retries images that failed offline (loaded ones come from memory cache).
    val reconnects = LocalReconnects.current
    val request = remember(url, px, reconnects) {
        ImageRequest.Builder(ctx).data(url).size(px.first, px.second).crossfade(crossfadeMs).build()
    }
    AsyncImage(
        model = request,
        contentDescription = null,
        contentScale = contentScale,
        modifier = modifier,
    )
}

/** Bumped by AppRoot whenever connectivity returns; read by images instead of each one subscribing. */
val LocalReconnects = androidx.compose.runtime.compositionLocalOf { 0 }

@Composable
fun cardColors() = CardDefaults.colors(containerColor = Palette.Surface, focusedContainerColor = Palette.Surface)

@Composable
fun cardBorder() = CardDefaults.border(
    focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = CardShape),
)

/** Landscape 16:9 card used in every home row. */
@Composable
fun LandscapeCard(
    title: Title,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 240.dp,
    subtitle: String? = null,
    progress: Float? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val height = width * 9f / 16f
    Card(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier.width(width).aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = cardBorder(),
        colors = cardColors(),
        glow = CardDefaults.glow(),
    ) {
        Box(Modifier.fillMaxSize()) {
            SizedImage(title.landscape, width, height, Modifier.fillMaxSize().drawWithCache {
                val top = size.height * 0.45f
                val shade = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f), startY = top, endY = size.height)
                onDrawWithContent {
                    drawContent()
                    drawRect(shade, topLeft = androidx.compose.ui.geometry.Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, size.height - top))
                }
            })
            // Title is always one line so a long name never pushes the S/E label out of the card.
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(title.displayTitle, style = MaterialTheme.typography.titleSmall, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = Palette.TextSecondary, maxLines = 1)
            }
            if (title.showFreeBadge) FreeBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            RatingChips(title, Modifier.align(Alignment.TopStart).padding(6.dp), small = true, max = 2)
            if (progress != null && progress > 0f) {
                // Netflix's red "watched so far" bar along the bottom edge.
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.3f)))
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0.03f, 1f)).height(4.dp).background(Palette.Red))
            }
        }
    }
}

/** Netflix "Top 10" tile: a giant outlined rank number tucked behind a portrait poster. */
@Composable
fun RankedCard(
    rank: Int,
    title: Title,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    posterWidth: Dp = 100.dp,
) {
    Box(modifier.width(posterWidth * 1.9f).height(posterWidth * 1.5f)) {
        Text(
            rank.toString(),
            modifier = Modifier.align(Alignment.BottomStart).offset(y = 26.dp),
            style = androidx.compose.ui.text.TextStyle(
                fontSize = (posterWidth.value * 1.45f).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Black,
                letterSpacing = (-8).sp,
                color = Palette.Background,
                drawStyle = androidx.compose.ui.graphics.drawscope.Stroke(width = 5f),
            ),
            color = Color(0xFF8C8C8C),
        )
        PosterCard(title, onClick, Modifier.align(Alignment.CenterEnd), width = posterWidth, onFocused = onFocused)
    }
}

/** Portrait 2:3 card used in grids (archive, search, my list). */
@Composable
fun PosterCard(
    title: Title,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 150.dp,
    onFocused: () -> Unit = {},
) {
    val height = width * 1.5f
    Card(
        onClick = onClick,
        modifier = modifier.width(width).aspectRatio(2f / 3f).onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = cardBorder(),
        colors = cardColors(),
    ) {
        Box(Modifier.fillMaxSize()) {
            SizedImage(title.poster ?: title.cover, width, height, Modifier.fillMaxSize())
            if (title.showFreeBadge) FreeBadge(Modifier.align(Alignment.TopEnd).padding(6.dp))
            RatingChips(title, Modifier.align(Alignment.BottomStart).padding(6.dp), small = true, max = if (width < 160.dp) 2 else 3)
        }
    }
}

@Composable
fun SlugCard(slug: Slug, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp = 220.dp) {
    val height = width * 9f / 16f
    Card(
        onClick = onClick,
        modifier = modifier.width(width).height(height),
        shape = CardDefaults.shape(CardShape),
        scale = CardDefaults.scale(focusedScale = 1.08f),
        border = cardBorder(),
        colors = cardColors(),
    ) {
        Box(Modifier.fillMaxSize()) {
            SizedImage(slug.image, width, height, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
            Text(
                slug.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
                maxLines = 2,
            )
        }
    }
}

@Composable
fun FreeBadge(modifier: Modifier = Modifier) {
    Text(
        "FREE",
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier.clip(RoundedCornerShape(3.dp)).background(Palette.Red).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** "2023 • +13 • 1h 52m • IMDb 7.8" — the Netflix metadata line. */
@Composable
fun MetaLine(title: Title, modifier: Modifier = Modifier, showRatings: Boolean = true) {
    val parts = buildList {
        title.year?.let { add(it) }
        title.age?.let { add(it) }
        when {
            (title.seasons ?: 0) > 0 -> add("${title.seasons} Season${if (title.seasons == 1) "" else "s"}")
            (title.runtimeMin ?: 0) > 0 -> add(formatRuntime(title.runtimeMin!!))
        }
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(parts.joinToString("  •  "), style = MaterialTheme.typography.titleSmall, color = Palette.TextSecondary)
        if (showRatings) RatingChips(title, Modifier.padding(start = 12.dp))
    }
}

/** One external rating: source label + value, tinted with the source's colour. */
data class Rating(val source: String, val value: String, val color: Color)

/** IMDb, Rotten Tomatoes, Metacritic and MyAnimeList — whichever the title has (30nama's own score is left out). */
fun Title.ratings(): List<Rating> = buildList {
    imdb?.let { add(Rating("IMDb", "%.1f".format(it), Palette.Imdb)) }
    rottenTomatoes?.let { add(Rating("RT", "$it%", if (it >= 60) Color(0xFFFA320A) else Color(0xFF8BC34A))) }
    metacritic?.let { add(Rating("MC", "$it", when { it >= 61 -> Color(0xFF66CC33); it >= 40 -> Color(0xFFFFCC33); else -> Color(0xFFFF4D4D) })) }
    myAnimeList?.let { add(Rating("MAL", "%.1f".format(it), Color(0xFF6C8CD5))) }
}

/** Compact chips: "IMDb 8.6  RT 97%  MC 63". [small] is for cards and posters. */
@Composable
fun RatingChips(title: Title, modifier: Modifier = Modifier, small: Boolean = false, max: Int = 4) {
    val ratings = title.ratings().take(max)
    if (ratings.isEmpty()) return
    val style = if (small) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(if (small) 4.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        ratings.forEach { r ->
            Row(
                Modifier.clip(RoundedCornerShape(3.dp)).background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = if (small) 4.dp else 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r.source, style = style, color = r.color)
                Text(" " + r.value, style = style, color = Color.White)
            }
        }
    }
}

fun formatRuntime(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = Palette.TextPrimary,
        modifier = modifier.fillMaxWidth(),
    )
}
