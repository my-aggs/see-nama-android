package com.seenama.tv.player

import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.seenama.tv.App
import com.seenama.tv.data.Episode
import com.seenama.tv.data.Prefs
import com.seenama.tv.data.Source
import com.seenama.tv.data.StreamInfo
import com.seenama.tv.data.WatchProgress
import com.seenama.tv.data.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Everything needed to start playback: which episode, where, and which stream variant. */
data class PlaybackPlan(
    val stream: StreamInfo,
    val episode: Episode,
    val startMs: Long,
    val source: Source,
    val audio: String,
) {
    val url: String get() = source.byAudio[audio] ?: source.byAudio.values.first()

    fun mediaItem(): MediaItem = MediaItem.Builder()
        .setUri(url)
        .setMimeType(Playback.mimeType(url))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(stream.title).build())
        .build()
}

/** The episode only plays on YouTube (some Farsihubz episodes are YouTube embeds). */
class ExternalVideo(val url: String) : Exception("external video")

object Playback {
    /** 30nama streams are HLS; Farsihubz movies are plain MP4 files and its live channels HLS. */
    fun mimeType(url: String): String {
        val path = url.substringBefore('?').lowercase()
        return if (path.endsWith(".mp4") || path.endsWith(".m4v")) MimeTypes.VIDEO_MP4 else MimeTypes.APPLICATION_M3U8
    }

    /**
     * Farsihubz episodes carry only their page; the video link is read from it right before playing
     * (and cached). Returns the stream with that episode filled in, so next/previous keep working.
     */
    suspend fun withSources(app: App, stream: StreamInfo, e: Episode): Pair<StreamInfo, Episode> {
        if (e.sources.isNotEmpty() || e.pageUrl == null) return stream to e
        return when (val v = app.repo.farsiVideo(e)) {
            is com.seenama.tv.data.FarsiVideo.YouTube -> throw ExternalVideo(v.url)
            is com.seenama.tv.data.FarsiVideo.Stream -> {
                val sources = v.urls.mapIndexed { i, u -> Source(if (v.urls.size > 1) "Server ${i + 1}" else "Auto", mapOf("default" to u)) }
                val resolved = e.copy(sources = sources)
                stream.copy(episodes = stream.episodes.map { if (it.fileId == e.fileId) resolved else it }) to resolved
            }
        }
    }

    suspend fun resolve(app: App, plan: PlaybackPlan): PlaybackPlan {
        val (stream, e) = withSources(app, plan.stream, plan.episode)
        if (e === plan.episode) return plan
        val (source, audio) = pickSource(e, app.prefs)
        return plan.copy(stream = stream, episode = e, source = source, audio = audio)
    }

    /**
     * Decides what to play. The locally recorded position (updated live while watching) wins over
     * the server's continue-watching data, which lags behind. A finished episode moves on to the
     * next one, like Netflix.
     */
    fun plan(stream: StreamInfo, requestedFileId: Long, local: WatchProgress?, prefs: Prefs): PlaybackPlan {
        val resumeFile = local?.fileId ?: stream.resumeFileId
        val resumeMs = local?.positionMs ?: (stream.resumeSec * 1000)
        val eps = stream.episodes
        var episode: Episode
        var start: Long
        if (requestedFileId > 0) {
            episode = eps.firstOrNull { it.fileId == requestedFileId } ?: eps.first()
            start = if (episode.fileId == resumeFile) resumeMs else 0L
        } else {
            val resumed = eps.firstOrNull { it.fileId == resumeFile }
            episode = resumed ?: eps.first()
            start = if (resumed != null) resumeMs else 0L
        }
        val finished = (local != null && local.fileId == episode.fileId && local.isFinished) ||
            (episode.creditsMs != null && start >= episode.creditsMs)
        if (finished && start > 0) {
            val next = eps.getOrNull(eps.indexOf(episode) + 1)
            if (requestedFileId <= 0 && next != null) episode = next
            start = 0L
        }
        val (source, audio) = pickSource(episode, prefs)
        return PlaybackPlan(stream, episode, start, source, audio)
    }

    fun pickSource(e: Episode, prefs: Prefs): Pair<Source, String> {
        if (e.sources.isEmpty()) return Source("Auto", emptyMap()) to "default" // filled in by resolve()
        val source = e.sources.firstOrNull { it.label.equals(prefs.quality, true) }
            ?: e.sources.firstOrNull { it.label.equals("Auto", true) }
            ?: e.sources.first()
        val audio = prefs.audioChannels.takeIf { it in source.byAudio } ?: "default"
        return source to (if (audio in source.byAudio) audio else source.byAudio.keys.first())
    }

    /**
     * While a player is only pre-buffering (not on screen) it stops after ~10 s of media so it
     * doesn't hog a slow connection; once adopted by the player screen it buffers normally.
     */
    // Every LoadControl method has a Java default (the old overloads throw), so Kotlin's `by`
    // delegation forwards nothing; each method ExoPlayer calls is forwarded explicitly.
    @OptIn(UnstableApi::class)
    class WarmableLoadControl(private val d: androidx.media3.exoplayer.DefaultLoadControl) : androidx.media3.exoplayer.LoadControl {
        @Volatile var warmOnly = false

        override fun shouldContinueLoading(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean =
            if (warmOnly && parameters.bufferedDurationUs >= 10_000_000) false else d.shouldContinueLoading(parameters)

        override fun onPrepared(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onPrepared(playerId)
        override fun onTracksSelected(
            parameters: androidx.media3.exoplayer.LoadControl.Parameters,
            trackGroups: androidx.media3.exoplayer.source.TrackGroupArray,
            trackSelections: Array<out androidx.media3.exoplayer.trackselection.ExoTrackSelection?>,
        ) = d.onTracksSelected(parameters, trackGroups, trackSelections)
        override fun onStopped(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onStopped(playerId)
        override fun onReleased(playerId: androidx.media3.exoplayer.analytics.PlayerId) = d.onReleased(playerId)
        override fun getAllocator(): androidx.media3.exoplayer.upstream.Allocator = d.allocator
        override fun getBackBufferDurationUs(playerId: androidx.media3.exoplayer.analytics.PlayerId): Long = d.getBackBufferDurationUs(playerId)
        override fun retainBackBufferFromKeyframe(playerId: androidx.media3.exoplayer.analytics.PlayerId): Boolean = d.retainBackBufferFromKeyframe(playerId)
        override fun shouldStartPlayback(parameters: androidx.media3.exoplayer.LoadControl.Parameters): Boolean = d.shouldStartPlayback(parameters)
        override fun shouldContinuePreloading(
            timeline: androidx.media3.common.Timeline,
            mediaPeriodId: androidx.media3.exoplayer.source.MediaSource.MediaPeriodId,
            bufferedDurationUs: Long,
        ): Boolean = d.shouldContinuePreloading(timeline, mediaPeriodId, bufferedDurationUs)
    }

    @OptIn(UnstableApi::class)
    fun createPlayer(app: App): ExoPlayer = createPlayerWithControl(app).first

    @OptIn(UnstableApi::class)
    fun createPlayerWithControl(app: App): Pair<ExoPlayer, WarmableLoadControl> {
        val dataSource = OkHttpDataSource.Factory(app.http)
        // Start after ~1s of media (fast start), then keep a deep buffer: the CDN is far and bursty.
        val loadControl = WarmableLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(30_000, 120_000, 1_000, 2_500)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()
        )
        val renderers = DefaultRenderersFactory(app)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val player = ExoPlayer.Builder(app, renderers)
            .setBandwidthMeter(bandwidthMeter(app))
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setLoadControl(loadControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                setSeekParameters(SeekParameters.CLOSEST_SYNC)
                // Subtitles are drawn by the app (RTL + bilingual), so embedded text tracks are off.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .apply { app.prefs.audioLanguage?.let { setPreferredAudioLanguage(it) } }
                    .build()
            }
        return player to loadControl
    }

    private var meter: androidx.media3.exoplayer.upstream.DefaultBandwidthMeter? = null

    /**
     * One bandwidth meter for every player, so what one video learns about the connection
     * carries over to the next. The first start assumes a modest 1.2 Mbps: the first segment
     * is small (fast start) and adaptive streaming climbs to 1080p within seconds — like Netflix.
     */
    @OptIn(UnstableApi::class)
    @Synchronized
    fun bandwidthMeter(app: App): androidx.media3.exoplayer.upstream.DefaultBandwidthMeter =
        meter ?: androidx.media3.exoplayer.upstream.DefaultBandwidthMeter.Builder(app)
            .setInitialBitrateEstimate(1_200_000)
            .build().also { meter = it }

    /** Next lower fixed resolution for decoder-error fallback ("Auto" → 720p, 1080p → 720p, …). */
    fun lowerSource(e: Episode, current: Source): Source? {
        fun height(s: Source) = Regex("""(\d{3,4})p""").find(s.label)?.groupValues?.get(1)?.toInt()
        val currentHeight = height(current) ?: 1081
        return e.sources.filter { (height(it) ?: Int.MAX_VALUE) < currentHeight }.maxByOrNull { height(it) ?: 0 }
    }
}

/** Parsed subtitle files, shared between pre-warming and the player. */
class SubtitleCache(private val app: App) {
    private val cache = LruCache<String, List<Cue>>(12)

    suspend fun load(url: String): List<Cue>? {
        cache[url]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                app.http.newCall(Request.Builder().url(url).build()).await().use { r ->
                    SubtitleParser.parse(decodeText(r.body!!.bytes()))
                }
            }.getOrNull()?.also { cache.put(url, it) }
        }
    }

    suspend fun loadAll(e: Episode): Map<String, List<Cue>> =
        e.subtitles.mapNotNull { (lang, url) -> load(url)?.let { lang to it } }.toMap()
}

/**
 * Netflix-style pre-buffering: when the user settles on something they're likely to play
 * (a details page, a Continue Watching card, an episode), a paused player is prepared at the
 * exact resume point and starts buffering. Opening the player then adopts it, so playback
 * starts almost immediately. Only one warm player exists at a time; it's released if unused.
 */
class PlayerWarmer(private val app: App) {
    class Warm(
        val postId: Long,
        val requestedFileId: Long,
        val plan: PlaybackPlan,
        val player: ExoPlayer,
        private val control: Playback.WarmableLoadControl,
    ) {
        /** Called when the player screen takes over: buffer normally from now on. */
        fun activate() { control.warmOnly = false }
    }

    // ExoPlayer must be created and used on the main thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var current: Warm? = null

    /** Set while the player screen is open: never start a second player behind it. */
    var playerActive = false
        set(v) { field = v; if (v) { job?.cancel(); expiry?.cancel() } }
    private var job: Job? = null
    private var expiry: Job? = null

    fun warm(postId: Long, free: Boolean, requestedFileId: Long = -1) {
        if (playerActive) return
        val c = current
        if (c != null && c.postId == postId && c.requestedFileId == requestedFileId) { scheduleExpiry(); return }
        job?.cancel()
        job = scope.launch {
            val stream = runCatching { app.repo.stream(postId, free) }.getOrNull() ?: return@launch
            val plan = runCatching {
                Playback.resolve(app, Playback.plan(stream, requestedFileId, app.repo.localProgress(postId), app.prefs))
            }.getOrNull() ?: return@launch
            if (playerActive) return@launch
            releaseCurrent()
            val (player, control) = Playback.createPlayerWithControl(app)
            control.warmOnly = true
            player.setMediaItem(plan.mediaItem(), plan.startMs)
            player.playWhenReady = false
            player.prepare()
            current = Warm(postId, requestedFileId, plan, player, control)
            scheduleExpiry()
            launch { app.subtitles.loadAll(plan.episode) }
        }
    }

    /** Hands over the warm player if it matches what the player screen is about to play. */
    fun take(postId: Long, requestedFileId: Long): Warm? {
        val c = current ?: return null
        val matches = c.postId == postId && (
            if (requestedFileId > 0) c.plan.episode.fileId == requestedFileId else c.requestedFileId <= 0
        )
        if (!matches) return null
        current = null
        expiry?.cancel()
        c.activate()
        return c
    }

    private fun scheduleExpiry() {
        expiry?.cancel()
        expiry = scope.launch { delay(90_000); releaseCurrent() }
    }

    /** Drops any pre-buffering, including a warm-up still being set up (so two players never compete). */
    fun release() {
        job?.cancel()
        expiry?.cancel()
        releaseCurrent()
    }

    private fun releaseCurrent() {
        current?.player?.release()
        current = null
    }
}
