package com.seenama.tv.ui.details

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.seenama.tv.App
import com.seenama.tv.data.ListGroup
import com.seenama.tv.data.Discovery
import com.seenama.tv.data.ShowInfo
import com.seenama.tv.data.Title
import com.seenama.tv.data.WikiSummary
import com.seenama.tv.data.Repository
import com.seenama.tv.data.StreamInfo
import com.seenama.tv.data.TitleDetails
import com.seenama.tv.player.PlayerWarmer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class DetailsViewModel(val repo: Repository, private val discovery: Discovery, private val warmer: PlayerWarmer, private val id: Long) : ViewModel() {
    private val _details = MutableStateFlow(repo.cachedDetails(id))
    val details: StateFlow<TitleDetails?> = _details
    private val _error = MutableStateFlow(false)
    val error: StateFlow<Boolean> = _error
    private val _stream = MutableStateFlow<StreamInfo?>(null)
    /** Episodes for series (only fetched when the user is allowed to stream). */
    val stream: StateFlow<StreamInfo?> = _stream

    // Enrichment from free sources (TVmaze, Wikipedia) and catalog-based recommendations.
    private val _show = MutableStateFlow<ShowInfo?>(null)
    val show: StateFlow<ShowInfo?> = _show
    private val _wiki = MutableStateFlow<WikiSummary?>(null)
    val wiki: StateFlow<WikiSummary?> = _wiki
    private val _similar = MutableStateFlow<List<Title>>(emptyList())
    val similar: StateFlow<List<Title>> = _similar
    private var enriched = false

    val library = repo.library
    val continueWatching = repo.continueWatching

    init { load() }

    fun load() {
        _error.value = false
        viewModelScope.launch {
            runCatching { repo.details(id) }
                .onSuccess { d ->
                    _details.value = d
                    enrich(d.title)
                    if (repo.canStream(d.title)) {
                        runCatching { repo.stream(id, d.title.freeStream) }.onSuccess { if (d.title.isSeries) _stream.value = it }
                        // The Play/Resume button has focus: start buffering exactly what it will play.
                        warm(-1)
                    }
                }
                .onFailure { if (_details.value == null) _error.value = true }
        }
    }

    private fun enrich(t: Title) {
        if (enriched) return
        enriched = true
        viewModelScope.launch { _similar.value = runCatching { repo.moreLikeThis(t) }.getOrDefault(emptyList()) }
        val imdb = t.imdbId ?: return
        if (t.isSeries) viewModelScope.launch { _show.value = discovery.show(imdb) }
        viewModelScope.launch { _wiki.value = discovery.wiki(imdb) }
    }

    private var warmJob: kotlinx.coroutines.Job? = null

    /** Pre-buffer [fileId] (or the resume target when -1) once focus has settled on it. */
    fun warm(fileId: Long, settleMs: Long = 0) {
        val t = _details.value?.title ?: return
        if (!repo.canStream(t)) return
        warmJob?.cancel()
        warmJob = viewModelScope.launch {
            if (settleMs > 0) kotlinx.coroutines.delay(settleMs)
            warmer.warm(id, t.freeStream, fileId)
        }
    }

    fun toggle(group: ListGroup) {
        viewModelScope.launch { repo.toggleList(group, id) }
    }

    companion object {
        fun factory(id: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer { (this[APPLICATION_KEY] as App).let { DetailsViewModel(it.repo, it.discovery, it.warmer, id) } }
        }
    }
}
