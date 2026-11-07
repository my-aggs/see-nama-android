package com.seenama.tv.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.seenama.tv.App
import com.seenama.tv.data.Repository
import com.seenama.tv.data.Row
import com.seenama.tv.data.SortOrder
import com.seenama.tv.data.Title
import com.seenama.tv.data.isFarsi
import com.seenama.tv.ui.Nav
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface HomeState {
    data object Loading : HomeState
    data object Error : HomeState
    data class Ready(val hero: List<Title>, val rows: List<Row>) : HomeState
}

private val FarsiShowGenres = setOf("Comedy", "Drama", "Reality Show", "Talk-Show", "Family", "Social", "Crime")

class HomeViewModel(private val repo: Repository, private val warmer: com.seenama.tv.player.PlayerWarmer, private val filter: HomeFilter) : ViewModel() {
    private val _state = MutableStateFlow<HomeState>(HomeState.Loading)
    val state: StateFlow<HomeState> = _state
    private val farsiRows = MutableStateFlow<List<Row>?>(null)

    init {
        if (filter == HomeFilter.All) {
            viewModelScope.launch {
                combine(repo.home, repo.continueWatching, repo.top10, repo.personalRows) { home, cw, top, personal ->
                    if (home == null) null
                    else {
                        // Netflix-like ordering: resume → today's Top 10 → personal picks → editorial rows.
                        val rows = buildList {
                            if (cw.isNotEmpty()) add(Row("continue", "Continue Watching", cw))
                            if (top.size >= 5) add(Row("top10", "Top 10 on 30nama Today", top, ranked = true))
                            addAll(home.rows.take(1))
                            addAll(personal)
                            addAll(home.rows.drop(1))
                        }
                        HomeState.Ready(home.hero.ifEmpty { home.rows.firstOrNull()?.items?.take(5).orEmpty() }, rows)
                    }
                }.collect { s -> if (s != null) _state.value = s }
            }
        }
        if (filter == HomeFilter.Farsi) {
            // Continue Watching here shows only Farsihubz titles; it updates live like the main one.
            viewModelScope.launch {
                combine(farsiRows, repo.continueWatching) { rows, cw -> rows to cw.filter { it.isFarsi } }.collect { (rows, cw) ->
                    if (rows == null) return@collect
                    val all = if (cw.isEmpty()) rows else listOf(Row("continue", "Continue Watching", cw)) + rows
                    val hero = rows.firstOrNull { it.key == "farsi:shows" }?.items?.filter { it.cover != null }?.take(6).orEmpty()
                    _state.value = HomeState.Ready(hero.ifEmpty { rows.first().items.take(5) }, all)
                }
            }
        }
        load()
    }

    /** Farsihubz: newest movies and series, then the biggest genres. */
    private suspend fun loadFarsi() {
        // Paint the last good rows from disk at once, then refresh from the site.
        if (farsiRows.value == null) runCatching { buildFarsi(cachedOnly = true) }
        runCatching { buildFarsi(cachedOnly = false) }.onFailure { if (farsiRows.value == null) throw it }
    }

    private suspend fun buildFarsi(cachedOnly: Boolean) = kotlinx.coroutines.coroutineScope {
        val movieGenres = async { runCatching { repo.farsi.genreList(show = false, cachedOnly) }.getOrDefault(emptyList()) }
        val showGenres = async { runCatching { repo.farsi.genreList(show = true, cachedOnly) }.getOrDefault(emptyList()) }
        val specs = buildList {
            add(Triple("farsi:movies", "New Iranian Movies", true))
            add(Triple("farsi:shows", "Series & Shows · Recently Updated", false))
            movieGenres.await().take(9).forEach { add(Triple(it.slug, "${it.name} Movies", true)) }
            showGenres.await().filter { it.name in FarsiShowGenres }.take(3).forEach { add(Triple(it.slug, "${it.name} Series", false)) }
        }
        val rows = specs.map { (slug, label, portrait) ->
            async {
                (if (cachedOnly) repo.farsi.cachedPage(slug, 20) else runCatching { repo.farsiPage(slug, 1, perPage = 20) }.getOrNull())?.items
                    ?.takeIf { it.size >= 3 }
                    ?.let { Row(slug, label, it, slug, portrait = portrait) }
            }
        }.awaitAll().filterNotNull()
        // Different genres overlap a lot: keep each title in the first row it appears in, past the first two.
        val seen = HashSet<Long>()
        val deduped = rows.mapIndexedNotNull { i, r ->
            val items = if (i < 2) r.items else r.items.filter { it.id !in seen }
            seen += r.items.map { it.id }
            r.copy(items = items).takeIf { items.size >= 4 }
        }
        if (deduped.isEmpty()) throw IllegalStateException("empty")
        farsiRows.value = deduped
    }

    fun load() {
        viewModelScope.launch {
            if (_state.value !is HomeState.Ready) _state.value = HomeState.Loading
            runCatching {
                when (filter) {
                    HomeFilter.All -> if (repo.home.value == null) repo.loadHome()
                    HomeFilter.Series -> _state.value = archiveRows("series")
                    HomeFilter.Movies -> _state.value = archiveRows("movies")
                    HomeFilter.Farsi -> loadFarsi()
                }
            }.onFailure { if (_state.value !is HomeState.Ready) _state.value = HomeState.Error }
        }
    }

    /** The Series/Movies tabs are built from the archive endpoint with different sort orders. */
    private suspend fun archiveRows(slug: String): HomeState.Ready {
        val sorts = listOf(
            SortOrder.Update to "New Arrivals",
            SortOrder.Favorite to "Popular",
            SortOrder.Imdb to "Top Rated on IMDb",
            SortOrder.Nama to "Top Rated on 30nama",
            SortOrder.Year to "Newest Releases",
        )
        val rows = kotlinx.coroutines.coroutineScope {
            sorts.map { (sort, label) ->
                async {
                    runCatching { repo.archive(slug, 1, sort) }.getOrNull()?.items
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { Row("$slug:${sort.key}", label, it, slug) }
                }
            }.awaitAll().filterNotNull()
        }
        if (rows.isEmpty()) throw IllegalStateException("empty")
        return HomeState.Ready(rows.first().items.take(6), rows)
    }

    private var settleJob: kotlinx.coroutines.Job? = null

    /**
     * Focus settled on [t]: prefetch its details and stream links. Continue Watching cards
     * (and the billboard's Play) start straight into the player, so those get a warm player.
     */
    fun onFocusSettled(t: Title, startsPlayback: Boolean) {
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            kotlinx.coroutines.delay(if (startsPlayback) 500 else 700)
            if (startsPlayback && repo.canStream(t)) warmer.warm(t.id, t.freeStream, -1)
            else repo.prefetch(t)
        }
    }

    fun play(t: Title, nav: Nav) {
        if (repo.canStream(t)) nav.player(t.id, t.freeStream) else nav.login()
    }

    companion object {
        fun factory(filter: HomeFilter): ViewModelProvider.Factory = viewModelFactory {
            initializer { (this[APPLICATION_KEY] as App).let { HomeViewModel(it.repo, it.warmer, filter) } }
        }
    }
}
