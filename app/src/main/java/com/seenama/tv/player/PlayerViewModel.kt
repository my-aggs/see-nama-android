package com.seenama.tv.player

import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
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
import com.seenama.tv.data.Repository
import com.seenama.tv.data.Source
import com.seenama.tv.data.StreamInfo
import com.seenama.tv.data.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class AudioOption(val groupIndex: Int, val trackIndex: Int, val language: String?, val label: String)

sealed interface PlayerUi {
    data object Loading : PlayerUi
    data class Error(val message: String) : PlayerUi
    data object NotAllowed : PlayerUi
    /** Plays only on YouTube: hand it to the YouTube app. */
    data class External(val url: String) : PlayerUi
    data class Ready(
        val stream: StreamInfo,
        val episode: Episode,
        val source: Source,
        val audioChannels: String,
    ) : PlayerUi
}

@OptIn(UnstableApi::class)
class PlayerViewModel(
    private val app: App,
    private val postId: Long,
    private val free: Boolean,
    private val requestedFileId: Long,
) : ViewModel() {
    private val repo: Repository = app.repo
    private val prefs: Prefs = app.prefs
    private val http: OkHttpClient = app.http

    private val _ui = MutableStateFlow<PlayerUi>(PlayerUi.Loading)
    val ui: StateFlow<PlayerUi> = _ui

    private val _subtitles = MutableStateFlow<Map<String, List<Cue>>>(emptyMap())
    val subtitles: StateFlow<Map<String, List<Cue>>> = _subtitles

    private val _audioOptions = MutableStateFlow<List<AudioOption>>(emptyList())
    val audioOptions: StateFlow<List<AudioOption>> = _audioOptions
    private val _selectedAudio = MutableStateFlow<String?>(prefs.audioLanguage)
    val selectedAudio: StateFlow<String?> = _selectedAudio

    private val _subtitleStyle = MutableStateFlow(SubtitleStyle.from(prefs))
    val subtitleStyle: StateFlow<SubtitleStyle> = _subtitleStyle

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _buffering = MutableStateFlow(true)
    val buffering: StateFlow<Boolean> = _buffering

    /** A player that was already buffering this title (see PlayerWarmer), if any. */
    private val openedAt = android.os.SystemClock.elapsedRealtime()
    private var reportedReady = false
    private var warm: PlayerWarmer.Warm? = app.warmer.take(postId, requestedFileId).also {
        if (it == null) app.warmer.release() // don't keep a second player buffering something else
        app.warmer.playerActive = true
    }
    /** True when playback started from a pre-buffered player. */
    private val prebuffered = warm != null
    val player: ExoPlayer = (warm?.player ?: Playback.createPlayer(app)).also { it.playWhenReady = true }

    private var subtitleJob: Job? = null
    private var contentStartMs = 0L

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (!isPlaying) reportProgress()
            }

            override fun onPlaybackStateChanged(state: Int) {
                _buffering.value = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
                if (state == Player.STATE_READY) logReady()
                if (state == Player.STATE_ENDED) next()
            }

            override fun onTracksChanged(tracks: Tracks) = updateAudioOptions(tracks)

            override fun onPlayerError(error: PlaybackException) = recover(error)
        })
        // A pre-buffered player may already be playing: its state changes happened before this listener existed.
        _isPlaying.value = player.isPlaying || (player.playWhenReady && player.playbackState == Player.STATE_READY)
        load()
        if (player.playbackState == Player.STATE_READY) logReady()
        viewModelScope.launch { progressLoop() }
    }

    private fun logReady() {
        if (reportedReady) return
        reportedReady = true
        val f = player.videoFormat
        android.util.Log.i("Playback", "ready after ${android.os.SystemClock.elapsedRealtime() - openedAt} ms (prebuffered=$prebuffered) at ${contentStartMs / 1000}s, video ${f?.height}p ${f?.bitrate?.div(1000)}kbps")
    }

    private var retries = 0
    private var recoveryJob: Job? = null
    /** Last known content position, kept across errors so recovery resumes exactly where it broke. */
    private var lastGoodPositionMs = 0L

    private val _reconnecting = MutableStateFlow(false)
    /** True while the player is recovering from a network/CDN error ("Reconnecting…" overlay). */
    val reconnecting: StateFlow<Boolean> = _reconnecting

    /**
     * Netflix-style recovery: never dump the user to an error screen for a hiccup.
     *  - Network errors: wait for connectivity, then retry with backoff (up to ~1 minute).
     *  - Expired/forbidden links (HTTP 403/404/410): fetch fresh stream links and resume.
     *  - Decoder/format errors: try the next lower quality once.
     */
    private fun recover(error: PlaybackException) {
        val ui = _ui.value as? PlayerUi.Ready ?: return
        val pos = player.currentPosition.takeIf { it > 0 } ?: lastGoodPositionMs
        if (recoveryJob?.isActive == true) return
        recoveryJob = viewModelScope.launch {
            _reconnecting.value = true
            val http = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
            when {
                // Farsihubz links don't expire; a dead link means trying its next server, if it has one.
                com.seenama.tv.data.FarsiIds.isFarsi(postId) && (http != null || error.errorCode in SOURCE_ERRORS) -> {
                    val next = ui.episode.sources.getOrNull(ui.episode.sources.indexOf(ui.source) + 1)
                    if (next != null) selectSource(next, "default", persist = false) else fail()
                }
                http == 403 || http == 404 || http == 410 -> {
                    // Signed CDN links expire; get new ones for the same episode.
                    val fresh = runCatching { repo.stream(postId, free, fresh = true) }.getOrNull()
                    val ep = fresh?.episodes?.firstOrNull { it.fileId == ui.episode.fileId }
                    if (fresh != null && ep != null) {
                        val src = ep.sources.firstOrNull { it.label == ui.source.label } ?: ep.sources.first()
                        val updated = ui.copy(stream = fresh, episode = ep, source = src)
                        _ui.value = updated
                        setContent(updated, pos)
                    } else fail()
                }
                error.errorCode in DECODER_ERRORS && retries++ < 2 -> {
                    // Step down to a lower fixed resolution for this session only (not saved as a preference).
                    val lower = Playback.lowerSource(ui.episode, ui.source)
                    if (lower != null) selectSource(lower, ui.audioChannels, persist = false) else fail()
                }
                else -> {
                    var attempt = 0
                    while (attempt < 6) {
                        app.connection.awaitNetwork(60_000)
                        delay(minOf(1_000L shl attempt, 15_000L))
                        player.seekTo(pos)
                        player.prepare()
                        player.play()
                        // Give it a moment to start buffering; a new error re-enters onPlayerError.
                        delay(3_000)
                        if (player.playerError == null) break
                        attempt++
                    }
                    if (player.playerError != null) fail()
                }
            }
            _reconnecting.value = false
        }
    }

    private fun fail() {
        _reconnecting.value = false
        _ui.value = PlayerUi.Error("We're having trouble playing this title right now.")
    }

    fun load() {
        _ui.value = PlayerUi.Loading
        warm?.let { w ->
            // Adopt the pre-buffered player: it's already prepared at the right position.
            warm = null
            val ready = PlayerUi.Ready(w.plan.stream, w.plan.episode, w.plan.source, w.plan.audio)
            contentStartMs = w.plan.startMs
            _ui.value = ready
            _buffering.value = player.playbackState != Player.STATE_READY
            loadSubtitles(w.plan.episode)
            return
        }
        viewModelScope.launch {
            val user = repo.user.value
            if (!free && !(repo.isLoggedIn && user?.allowedToStream == true)) {
                _ui.value = PlayerUi.NotAllowed
                return@launch
            }
            val stream = runCatching { repo.stream(postId, free) }.getOrElse {
                _ui.value = PlayerUi.Error("Couldn't load this video"); return@launch
            }
            android.util.Log.i("Playback", "stream info after ${android.os.SystemClock.elapsedRealtime() - openedAt} ms")
            val plan = runCatching { Playback.resolve(app, Playback.plan(stream, requestedFileId, repo.localProgress(postId), prefs)) }.getOrElse {
                _ui.value = if (it is ExternalVideo) PlayerUi.External(it.url) else PlayerUi.Error("Couldn't load this video")
                return@launch
            }
            play(plan.stream, plan.episode, plan.startMs, plan.source, plan.audio)
        }
    }

    private var resolveJob: Job? = null

    private fun play(stream: StreamInfo, e: Episode, startMs: Long, source: Source? = null, audio: String? = null) {
        if (e.sources.isEmpty() && e.pageUrl != null) {
            // Farsihubz: look up this episode's video link first.
            player.stop()
            _ui.value = PlayerUi.Loading
            resolveJob?.cancel()
            resolveJob = viewModelScope.launch {
                runCatching { Playback.withSources(app, stream, e) }
                    .onSuccess { (s, resolved) -> play(s, resolved, startMs) }
                    .onFailure { _ui.value = if (it is ExternalVideo) PlayerUi.External(it.url) else PlayerUi.Error("Couldn't load this episode") }
            }
            return
        }
        val (s, a) = if (source != null && audio != null) source to audio else Playback.pickSource(e, prefs)
        contentStartMs = startMs
        val ready = PlayerUi.Ready(stream, e, s, a)
        _ui.value = ready
        retries = 0
        loadSubtitles(e)
        setContent(ready, startMs)
    }

    private fun setContent(ui: PlayerUi.Ready, startMs: Long) {
        val url = ui.source.byAudio[ui.audioChannels] ?: ui.source.byAudio.values.first()
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(Playback.mimeType(url))
            .setMediaMetadata(MediaMetadata.Builder().setTitle(ui.stream.title).build())
            .build()
        player.setMediaItem(item, startMs)
        player.prepare()
        player.play()
    }

    fun playEpisode(e: Episode) {
        val ui = _ui.value as? PlayerUi.Ready ?: return
        reportProgress()
        play(ui.stream, e, 0L)
    }

    fun next(): Boolean {
        val ui = _ui.value as? PlayerUi.Ready ?: return false
        val i = ui.stream.episodes.indexOf(ui.episode)
        val n = ui.stream.episodes.getOrNull(i + 1) ?: return false
        playEpisode(n)
        return true
    }

    fun previous(): Boolean {
        val ui = _ui.value as? PlayerUi.Ready ?: return false
        val i = ui.stream.episodes.indexOf(ui.episode)
        val p = ui.stream.episodes.getOrNull(i - 1) ?: return false
        playEpisode(p)
        return true
    }

    fun hasNext(): Boolean {
        val ui = _ui.value as? PlayerUi.Ready ?: return false
        return ui.stream.episodes.indexOf(ui.episode) < ui.stream.episodes.lastIndex
    }

    /** Switch quality / audio channels, keeping the position. */
    fun selectSource(source: Source, audio: String, persist: Boolean = true) {
        val ui = _ui.value as? PlayerUi.Ready ?: return
        if (persist && !com.seenama.tv.data.FarsiIds.isFarsi(postId)) {
            prefs.quality = source.label
            prefs.qualityChosen = true
            prefs.audioChannels = audio
        }
        val pos = player.currentPosition
        val updated = ui.copy(source = source, audioChannels = if (audio in source.byAudio) audio else "default")
        _ui.value = updated
        setContent(updated, pos)
    }

    fun selectAudio(option: AudioOption) {
        prefs.audioLanguage = option.language
        _selectedAudio.value = option.language
        val group = player.currentTracks.groups.getOrNull(option.groupIndex) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, option.trackIndex))
            .apply { option.language?.let { setPreferredAudioLanguage(it) } }
            .build()
    }

    fun updateSubtitleStyle(style: SubtitleStyle) {
        _subtitleStyle.value = style
        style.save(prefs)
    }

    private fun updateAudioOptions(tracks: Tracks) {
        val opts = mutableListOf<AudioOption>()
        tracks.groups.forEachIndexed { gi, g ->
            if (g.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (ti in 0 until g.length) {
                val f = g.getTrackFormat(ti)
                val label = f.label ?: languageName(f.language) ?: "Track ${opts.size + 1}"
                opts += AudioOption(gi, ti, f.language, label)
            }
            if (g.isSelected) {
                val sel = (0 until g.length).firstOrNull { g.isTrackSelected(it) }
                if (sel != null) _selectedAudio.value = g.getTrackFormat(sel).language
            }
        }
        _audioOptions.value = opts.distinctBy { it.language to it.label }
    }

    private fun loadSubtitles(e: Episode) {
        subtitleJob?.cancel()
        _subtitles.value = emptyMap()
        subtitleJob = viewModelScope.launch { _subtitles.value = app.subtitles.loadAll(e) }
    }

    // ---- Progress reporting (the server's continue-watching) ----

    private suspend fun progressLoop() {
        var ticks = 0
        while (true) {
            delay(1_000)
            if (player.isPlaying) {
                lastGoodPositionMs = player.currentPosition
                if (++ticks % 15 == 0) reportProgress()
            }
        }
    }

    fun reportProgress() {
        val ui = _ui.value as? PlayerUi.Ready ?: return
        val pos = player.currentPosition
        val dur = player.duration.takeIf { it > 0 } ?: return
        app.scope.launch { repo.reportProgress(ui.stream.postId.takeIf { it > 0 } ?: postId, ui.episode, pos, dur) }
    }

    override fun onCleared() {
        val ui = _ui.value as? PlayerUi.Ready
        if (ui != null && player.duration > 0) {
            repo.finishPlayback(ui.stream.postId.takeIf { it > 0 } ?: postId, ui.episode, player.currentPosition, player.duration)
        }
        player.release()
        app.warmer.playerActive = false
    }

    companion object {
        private val SOURCE_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        )
        private val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )

        fun factory(postId: Long, free: Boolean, fileId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { PlayerViewModel(this[APPLICATION_KEY] as App, postId, free, fileId) }
        }
    }
}

/** Persian subtitles are frequently Windows-1256 rather than UTF-8. */
internal fun decodeText(bytes: ByteArray): String {
    val utf8 = bytes.toString(Charsets.UTF_8)
    return if ('�' in utf8) String(bytes, charset("windows-1256")) else utf8
}

private fun languageName(code: String?): String? = when (code?.lowercase()?.take(2)) {
    null, "", "un" -> null
    "fa", "pe" -> "Persian (Dubbed)"
    "en" -> "English (Original)"
    "ja" -> "Japanese"
    "ko" -> "Korean"
    "fr" -> "French"
    "es" -> "Spanish"
    "de" -> "German"
    "tr" -> "Turkish"
    "ar" -> "Arabic"
    "hi" -> "Hindi"
    "zh" -> "Chinese"
    "it" -> "Italian"
    "ru" -> "Russian"
    else -> java.util.Locale(code).getDisplayLanguage(java.util.Locale("fa")).ifBlank { code }
}
