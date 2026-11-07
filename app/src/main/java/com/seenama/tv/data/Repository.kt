package com.seenama.tv.data

import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/** Where the user stopped watching, recorded locally the moment it happens. */
data class WatchProgress(
    val postId: Long,
    val fileId: Long,
    val season: Int?,
    val episode: Int?,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val fraction: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val isFinished: Boolean get() = durationMs > 0 && positionMs >= durationMs * 0.95
}

enum class ListGroup(val key: String, val label: String) {
    Watchlist("watchlist", "Watchlist"),
    Favorite("favorite", "Favorites"),
    Notify("notify", "Notify me"),
}

enum class SortOrder(val key: String, val label: String) {
    Relevant("relevant", "Relevant"),
    Update("update", "Recently updated"),
    Year("year", "Release year"),
    Favorite("favorite", "Most popular"),
    Imdb("imdb-rate", "IMDb rating"),
    Nama("30nama-rate", "30nama rating"),
}

class Repository(
    private val api: Api,
    private val prefs: Prefs,
    private val disk: ApiCache,
    private val scope: CoroutineScope,
    val farsi: FarsiCatalog,
) {

    private val _user = MutableStateFlow(prefs.userJson?.let { runCatching { User.parse(AppJson.parseToJsonElement(it)) }.getOrNull() })
    val user: StateFlow<User?> = _user.asStateFlow()
    val isLoggedIn: Boolean get() = prefs.session != null

    private val _library = MutableStateFlow(Library())
    val library: StateFlow<Library> = _library.asStateFlow()

    private val _continueWatching = MutableStateFlow<List<Title>>(emptyList())
    val continueWatching: StateFlow<List<Title>> = _continueWatching.asStateFlow()

    private val _home = MutableStateFlow<Home?>(null)
    val home: StateFlow<Home?> = _home.asStateFlow()

    private val _menu = MutableStateFlow<Pair<List<Slug>, List<Slug>>?>(null)
    /** (categories, genres) */
    val menu: StateFlow<Pair<List<Slug>, List<Slug>>?> = _menu.asStateFlow()

    private val _top10 = MutableStateFlow<List<Title>>(emptyList())
    val top10: StateFlow<List<Title>> = _top10.asStateFlow()

    private val _personalRows = MutableStateFlow<List<Row>>(emptyList())
    /** "Because you watched …" rows, derived from Continue Watching. */
    val personalRows: StateFlow<List<Row>> = _personalRows.asStateFlow()

    private val _notifications = MutableStateFlow<NotificationsState>(NotificationsState())
    val notifications: StateFlow<NotificationsState> = _notifications.asStateFlow()

    /** Emits notifications that arrived while the app is open (for the toast). */
    private val _incoming = MutableSharedFlow<AppNotification>(extraBufferCapacity = 4)
    val incoming: SharedFlow<AppNotification> = _incoming.asSharedFlow()

    private val detailsCache = LruCache<Long, TitleDetails>(64)
    /** Every title seen in any list, so a locally-started show can appear in Continue Watching. */
    private val titleIndex = LruCache<Long, Title>(600)
    private fun index(list: List<Title>) = list.onEach { titleIndex.put(it.id, it) }

    private var serverContinueWatching: List<Title> = emptyList()
    private val _local = MutableStateFlow<Map<Long, WatchProgress>>(emptyMap())
    /** Live local watch positions by title id (drives Resume buttons and progress bars). */
    val watchProgress: StateFlow<Map<Long, WatchProgress>> = _local.asStateFlow()
    @Volatile private var localLoaded = false
    private val pageCache = LruCache<String, Pair<Long, Page<Title>>>(48)
    private val streamCache = LruCache<Long, Pair<Long, StreamInfo>>(8)

    /** Disk-cache keys are per account so a sign-out/sign-in never shows another user's lists. */
    private fun key(name: String) = "${_user.value?.id ?: "guest"}-$name"

    // ---- Startup ----

    /**
     * Loads the last good responses from disk so the UI can render instantly.
     * Returns true when there is enough to show the home screen.
     */
    suspend fun restoreFromDisk(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        val home = async { disk.get(key("home"))?.json?.let(::parseHome) }
        val menu = async { disk.get(key("menu"))?.json?.let(::parseMenu) }
        val cw = async { disk.get(key("cw"))?.json?.let(::parseContinueWatching) }
        val lib = async { disk.get(key("library"))?.json?.let(::parseLibrary) }
        val notes = async { disk.get(key("notifications"))?.json?.let(::parseNotifications) }
        val top = async { disk.get(key("top10"))?.json?.let(::parseTop10) }
        top.await()?.let { if (_top10.value.isEmpty()) _top10.value = it }
        home.await()?.let { if (_home.value == null) _home.value = it }
        menu.await()?.let { if (_menu.value == null) _menu.value = it }
        loadLocalProgress()
        mergeContinueWatching()
        restoreFarsiTitles()
        if (isLoggedIn) {
            cw.await()?.let { if (serverContinueWatching.isEmpty()) { serverContinueWatching = it; mergeContinueWatching() } }
            lib.await()?.let { if (_library.value == Library()) _library.value = it }
            notes.await()?.let { if (_notifications.value.items.isEmpty()) _notifications.value = it }
        }
        _home.value != null
    }

    /** Mirrors the official app's splash: init API, then fetch everything the home screen needs in parallel. */
    suspend fun bootstrap() = coroutineScope {
        if (!api.initialize()) throw ApiException("no reachable server")
        val u = async { runCatching { refreshUser() } }
        val h = async { loadHome() }
        val m = async { runCatching { loadMenu() } }
        val c = async { runCatching { refreshContinueWatching() } }
        val n = async { runCatching { refreshNotifications() } }
        val t = async { runCatching { loadTop10() } }
        h.await(); u.await(); m.await(); c.await(); n.await(); t.await()
        runCatching { refreshLibrary() }
        runCatching { buildPersonalRows() }
    }

    /** Background refresh used after a cached cold start and when connectivity returns. */
    fun refreshAllInBackground() {
        scope.launch { runCatching { bootstrap() } }
    }

    suspend fun refreshUser() {
        if (!isLoggedIn) return
        val r = api.call("user")
        if (!r.success) { logout(); return }
        prefs.userJson = r.result.toString()
        _user.value = User.parse(r.result)
    }

    // ---- Home / menu ----

    suspend fun loadHome(): Home {
        val r = api.result("home_stream")
        disk.put(key("home"), r)
        return parseHome(r).also { _home.value = it }
    }

    private fun parseHome(r: JsonElement?): Home {
        val hero = index(Title.list(r.path("hero_header.posts")))
        val rows = listOf(
            Row("suggested", "Recommended for You", Title.list(r.path("suggested.posts")), "suggested"),
            Row("movies", "Movies", Title.list(r.path("movies.posts")), "movies"),
            Row("series", "TV Shows", Title.list(r.path("series.posts")), "series"),
            Row("anime", "Anime", Title.list(r.path("anime.posts")), "anime"),
        ).filter { it.items.isNotEmpty() }
        rows.forEach { index(it.items) }
        return Home(hero, rows)
    }

    suspend fun loadMenu() {
        val r = api.result("menu")
        disk.put(key("menu"), r)
        _menu.value = parseMenu(r)
    }

    private fun parseMenu(r: JsonElement?): Pair<List<Slug>, List<Slug>> {
        fun slugs(e: JsonElement?) = e.arr().mapNotNull { s ->
            val slug = s.firstStr("slug") ?: return@mapNotNull null
            if (slug == "coming-soon") return@mapNotNull null
            Slug(s.firstStr("name") ?: slug, slug, s.firstStr("image.webp.full", "image.jpg.full", "image.webp.thumb"), s["count"].int())
        }
        return slugs(r["categories"]) to slugs(r["genres"])
    }

    /** The full `home` feed carries a Top 10 the streaming-only feed lacks. */
    suspend fun loadTop10() {
        val r = api.result("home")
        val top = r?.obj()?.get("top_10")
        disk.put(key("top10"), top)
        _top10.value = parseTop10(top)
    }

    private fun parseTop10(r: JsonElement?) = index(Title.list(r["posts"]).filter { it.streamable }.take(10))

    // ---- Recommendations (from the 30nama catalog, so everything is playable) ----

    private fun typeSlug(t: Title): String? = when (t.titleType ?: if (t.isSeries) "series" else "movie") {
        "series" -> "series"
        "anime" -> "anime"
        "movie" -> "movies"
        else -> null
    }

    /** Same primary genre + same kind (series/movie/anime), best rated first. */
    suspend fun moreLikeThis(t: Title, exclude: Set<Long> = emptySet()): List<Title> {
        if (t.isFarsi) return index(farsi.moreLikeThis(t).filter { it.id !in exclude })
        val genre = t.genres.firstOrNull()?.slug ?: return emptyList()
        val type = typeSlug(t)
        val slugs = listOfNotNull(genre, type)
        val r = runCatching {
            api.result("archive", mapOf("slugs" to slugs, "streamonly" to true, "page" to 1, "orderby" to SortOrder.Imdb.key, "order" to "DESC"))
        }.getOrNull()
        val items = Title.list(r["posts"]).filter { it.id != t.id && it.id !in exclude && it.streamable }
        // A second genre in common makes a better match: rank those first.
        val second = t.genres.drop(1).map { it.slug }.toSet()
        return items.sortedByDescending { c -> c.genres.count { it.slug in second } }.take(20)
    }

    suspend fun buildPersonalRows() {
        val cw = _continueWatching.value
        if (cw.isEmpty()) { _personalRows.value = emptyList(); return }
        val seen = cw.map { it.id }.toSet()
        _personalRows.value = coroutineScope {
            cw.distinctBy { it.genres.firstOrNull()?.slug }.take(2).map { t ->
                async {
                    moreLikeThis(t, seen).takeIf { it.size >= 4 }?.let { Row("byw-${t.id}", "Because you watched ${t.displayTitle}", it) }
                }
            }.mapNotNull { it.await() }
        }
    }

    // ---- Continue watching ----

    suspend fun refreshContinueWatching() {
        if (!isLoggedIn) { loadLocalProgress(); serverContinueWatching = emptyList(); mergeContinueWatching(); return }
        val r = api.result("continue_watching")
        disk.put(key("cw"), r)
        loadLocalProgress()
        serverContinueWatching = parseContinueWatching(r)
        mergeContinueWatching()
    }

    private fun parseContinueWatching(r: JsonElement?) =
        index(Title.list(r["posts"]).filter { it.titleEn != null || it.titleFa != null })

    /** Farsihubz titles in Continue Watching aren't in any server list: look them up (disk-cached). */
    private fun restoreFarsiTitles() {
        val missing = _local.value.keys.filter { FarsiIds.isFarsi(it) && titleIndex[it] == null && detailsCache[it] == null }
        if (missing.isEmpty()) return
        scope.launch {
            missing.forEach { runCatching { details(it) } }
            mergeContinueWatching()
        }
    }

    // ---- Local watch progress ----

    fun localProgress(postId: Long): WatchProgress? = _local.value[postId]?.takeIf { it.fresh() }

    /**
     * 30nama positions only need to bridge the lag of the server's list; Farsihubz has no server
     * list, so its positions are the only record and are kept much longer.
     */
    private fun WatchProgress.fresh(now: Long = System.currentTimeMillis()) =
        now - updatedAt < if (FarsiIds.isFarsi(postId)) FARSI_PROGRESS_TTL_MS else LOCAL_PROGRESS_TTL_MS

    /** Records progress locally (instant UI update) — the server's continue-watching lags behind. */
    fun recordProgress(p: WatchProgress) {
        _local.update { it + (p.postId to p) }
        mergeContinueWatching()
        scope.launch { saveLocalProgress() }
    }

    /**
     * Continue Watching = locally watched titles first (most recent first, with their live position),
     * then the server's list. Finished movies drop off, like Netflix.
     */
    private fun mergeContinueWatching() {
        val now = System.currentTimeMillis()
        val local = _local.value.values.filter { it.fresh(now) }.sortedByDescending { it.updatedAt }
        val server = serverContinueWatching.associateBy { it.id }
        val overridden = local.mapNotNull { p ->
            val base = server[p.postId] ?: titleIndex[p.postId] ?: detailsCache[p.postId]?.title ?: return@mapNotNull null
            if (p.isFinished && !base.isSeries) return@mapNotNull null
            base.copy(
                cwSeason = p.season?.toString(),
                cwEpisode = p.episode?.toString(),
                cwProgress = p.fraction,
                cwDisplayTime = null,
            )
        }
        val localIds = local.map { it.postId }.toSet()
        _continueWatching.value = overridden + serverContinueWatching.filter { it.id !in localIds }
    }

    private suspend fun loadLocalProgress() {
        if (localLoaded) return
        localLoaded = true
        val arr = disk.get(key("progress"))?.json.arr()
        val loaded = arr.mapNotNull { j ->
            WatchProgress(
                postId = j["postId"].long() ?: return@mapNotNull null,
                fileId = j["fileId"].long() ?: return@mapNotNull null,
                season = j["season"].int(), episode = j["episode"].int(),
                positionMs = j["positionMs"].long() ?: 0, durationMs = j["durationMs"].long() ?: 0,
                updatedAt = j["updatedAt"].long() ?: 0,
            )
        }.associateBy { it.postId }
        _local.update { loaded + it }
    }

    private suspend fun saveLocalProgress() {
        val now = System.currentTimeMillis()
        val json = kotlinx.serialization.json.buildJsonArray {
            _local.value.values.filter { it.fresh(now) }.forEach { p ->
                add(kotlinx.serialization.json.buildJsonObject {
                    put("postId", kotlinx.serialization.json.JsonPrimitive(p.postId))
                    put("fileId", kotlinx.serialization.json.JsonPrimitive(p.fileId))
                    p.season?.let { put("season", kotlinx.serialization.json.JsonPrimitive(it)) }
                    p.episode?.let { put("episode", kotlinx.serialization.json.JsonPrimitive(it)) }
                    put("positionMs", kotlinx.serialization.json.JsonPrimitive(p.positionMs))
                    put("durationMs", kotlinx.serialization.json.JsonPrimitive(p.durationMs))
                    put("updatedAt", kotlinx.serialization.json.JsonPrimitive(p.updatedAt))
                })
            }
        }
        disk.put(key("progress"), json)
    }

    suspend fun dismissContinueWatching(id: Long) {
        _local.update { it - id }
        serverContinueWatching = serverContinueWatching.filterNot { it.id == id }
        _continueWatching.update { list -> list.filterNot { it.id == id } }
        scope.launch { saveLocalProgress() }
        if (!FarsiIds.isFarsi(id)) runCatching { api.call("continue_watching_dismiss", mapOf("id" to id)) }
    }

    // ---- Lists ----

    suspend fun refreshLibrary() {
        if (!isLoggedIn) return
        val r = api.result("user_mylist")
        disk.put(key("library"), r)
        _library.value = parseLibrary(r)
    }

    private fun parseLibrary(r: JsonElement?): Library {
        fun ids(group: String) = r.path("$group.post").arr().mapNotNull { it.long() ?: it["id"].long() }.toSet()
        return Library(favorite = ids("favorite"), watchlist = ids("watchlist"), notify = ids("notify"))
    }

    /** Optimistic toggle; the server endpoint toggles membership. Returns false when it had to roll back. */
    suspend fun toggleList(group: ListGroup, id: Long): Boolean {
        fun Set<Long>.flip() = if (id in this) this - id else this + id
        val before = _library.value
        _library.value = when (group) {
            ListGroup.Favorite -> before.copy(favorite = before.favorite.flip())
            ListGroup.Watchlist -> before.copy(watchlist = before.watchlist.flip())
            ListGroup.Notify -> before.copy(notify = before.notify.flip())
        }
        pageCache.evictAll()
        val ok = runCatching { api.call("user_list", mapOf("action" to "add", "group" to group.key, "post_id" to id)).success }.getOrDefault(false)
        if (!ok) _library.value = before
        return ok
    }

    // ---- Notifications ----

    suspend fun refreshNotifications() {
        if (!isLoggedIn) return
        val r = api.result("notification", mapOf("page" to 1))
        disk.put(key("notifications"), r)
        val fresh = parseNotifications(r)
        val known = _notifications.value.items.map { it.id }.toSet()
        // Only toast genuinely new items, and never on the very first load.
        if (known.isNotEmpty()) fresh.items.filter { !it.read && it.id !in known }.forEach { _incoming.tryEmit(it) }
        _notifications.value = fresh
    }

    private fun parseNotifications(r: JsonElement?) = NotificationsState(
        items = r["notifications"].arr().mapNotNull(AppNotification::parse),
        unread = r["total_unread"].int() ?: 0,
    )

    /** Debug builds: re-announce the latest notification to exercise the toast. */
    fun debugAnnounceLatest() { _notifications.value.items.firstOrNull()?.let { _incoming.tryEmit(it) } }

    suspend fun markNotificationRead(id: Long) {
        val current = _notifications.value
        val target = current.items.firstOrNull { it.id == id } ?: return
        if (target.read) return
        _notifications.value = current.copy(
            items = current.items.map { if (it.id == id) it.copy(read = true) else it },
            unread = (current.unread - 1).coerceAtLeast(0),
        )
        runCatching { api.call("notification_read", mapOf("notification_id" to id)) }
    }

    suspend fun markAllNotificationsRead() {
        _notifications.value.items.filter { !it.read }.forEach { markNotificationRead(it.id) }
    }

    /** Polls for new notifications while the app is in the foreground. */
    suspend fun pollNotifications(intervalMs: Long = 3 * 60_000) {
        while (true) {
            delay(intervalMs)
            runCatching { refreshNotifications() }
        }
    }

    // ---- Titles ----

    suspend fun details(id: Long): TitleDetails {
        detailsCache[id]?.let { return it }
        val d = if (FarsiIds.isFarsi(id)) farsi.details(id) else TitleDetails.parse(api.result("single", mapOf("post_id" to id))) ?: throw ApiException("not found", retryable = false)
        detailsCache.put(id, d)
        titleIndex.put(id, d.title)
        return d
    }

    fun cachedDetails(id: Long): TitleDetails? = detailsCache[id]

    /**
     * Stream links carry tokens, so cached copies are only reused briefly
     * (details screen → player). [fresh] forces a refetch (e.g. expired links).
     */
    suspend fun stream(id: Long, free: Boolean, fresh: Boolean = false): StreamInfo {
        if (FarsiIds.isFarsi(id)) return farsi.stream(id, fresh)
        if (!fresh) streamCache[id]?.let { (at, s) -> if (System.currentTimeMillis() - at < 3 * 60_000) return s }
        val r = api.result("stream", mapOf("post_id" to id, "free_stream" to free))
        val s = StreamInfo.parse(r)?.takeIf { it.episodes.isNotEmpty() } ?: throw ApiException("no stream", retryable = false)
        streamCache.put(id, System.currentTimeMillis() to s)
        return s
    }

    suspend fun search(query: String, page: Int): Page<Title> = paged("search:$query:$page") {
        api.result("search", mapOf("query" to query, "streamonly" to true, "page" to page))
    }

    suspend fun archive(slug: String, page: Int, sort: SortOrder): Page<Title> =
        if (slug.startsWith("farsi:")) farsiPage(slug, page) else paged("archive:$slug:$page:${sort.key}") {
        api.result("archive", mapOf("slugs" to listOf(slug), "streamonly" to true, "page" to page, "orderby" to sort.key, "order" to "DESC"))
    }

    // ---- Farsihubz ----

    suspend fun farsiPage(slug: String, page: Int, perPage: Int = 24): Page<Title> =
        farsi.page(slug, page, perPage = perPage).also { index(it.items) }

    suspend fun farsiSearch(query: String): List<Title> = index(farsi.search(query))

    /** An episode's video link, read from its page just before playing. */
    suspend fun farsiVideo(e: Episode): FarsiVideo = farsi.resolve(e)

    suspend fun userList(group: ListGroup, page: Int): Page<Title> = paged("list:${group.key}:$page") {
        api.result("user_list", mapOf("action" to "list", "group" to group.key, "page" to page, "orderby" to "add_list", "order" to "DESC"))
    }

    private suspend fun paged(key: String, fetch: suspend () -> JsonElement?): Page<Title> {
        pageCache[key]?.let { (at, page) -> if (System.currentTimeMillis() - at < PAGE_TTL_MS) return page }
        val r = fetch()
        val page = Page(index(Title.list(r["posts"])), r["page"].int() ?: 1, r["pages"].int() ?: 1)
        pageCache.put(key, System.currentTimeMillis() to page)
        return page
    }

    // ---- Playback progress ----

    private var lastReport: Triple<Long, Long, Long>? = null // fileId, timeSec, wallclock

    /** Records the position locally right away, then reports it to the server (deduplicated). */
    suspend fun reportProgress(postId: Long, episode: Episode, positionMs: Long, durationMs: Long) {
        if (positionMs <= 0 || durationMs <= 0) return
        recordProgress(WatchProgress(postId, episode.fileId, episode.season, episode.episode ?: episode.episodeNumber, positionMs, durationMs, System.currentTimeMillis()))
        sendProgress(postId, episode.fileId, episode.token, positionMs / 1000, durationMs / 1000)
    }

    private suspend fun sendProgress(postId: Long, fileId: Long, token: String?, timeSec: Long, durationSec: Long) {
        if (FarsiIds.isFarsi(postId)) return // no server-side history; recorded locally only
        val u = _user.value ?: return
        if (!u.allowedToStream || timeSec <= 0) return
        // Pause + back + screen close can all report the same position within a second.
        lastReport?.let { (f, t, at) -> if (f == fileId && kotlin.math.abs(t - timeSec) < 2 && System.currentTimeMillis() - at < 5_000) return }
        lastReport = Triple(fileId, timeSec, System.currentTimeMillis())
        runCatching {
            api.call(
                "observer",
                mapOf("post_id" to postId, "file_id" to fileId, "user_id" to u.id, "time" to timeSec, "duration" to durationSec, "token" to token),
                retries = 1,
            )
        }
    }

    /** After leaving the player: record + report the final position, then sync with the server. */
    fun finishPlayback(postId: Long, episode: Episode, positionMs: Long, durationMs: Long) {
        if (positionMs > 0 && durationMs > 0) {
            recordProgress(WatchProgress(postId, episode.fileId, episode.season, episode.episode ?: episode.episodeNumber, positionMs, durationMs, System.currentTimeMillis()))
        }
        streamCache.remove(postId) // its resume info is now stale
        scope.launch {
            sendProgress(postId, episode.fileId, episode.token, positionMs / 1000, durationMs / 1000)
            if (detailsCache[postId] == null && titleIndex[postId] == null) runCatching { details(postId) }.onSuccess { mergeContinueWatching() }
            runCatching { refreshContinueWatching() }
            runCatching { buildPersonalRows() }
        }
    }

    // ---- Prefetching (focus-driven) ----

    /** Warm caches for a title the user is hovering, so opening it is instant. */
    fun prefetch(t: Title) {
        scope.launch {
            runCatching { details(t.id) }
            if (canStream(t)) runCatching { stream(t.id, t.freeStream) }
        }
    }

    // ---- Auth ----

    /** Returns (code, url) for the QR login screen. */
    suspend fun qrCode(): Pair<String, String> {
        val r = api.result("qrlogin_code")
        return (r.firstStr("code") ?: "") to (r.firstStr("url") ?: "")
    }

    /** Polls once; true when the code has been approved and the session is stored. */
    suspend fun qrPoll(code: String): Boolean {
        val r = api.call("qrlogin_login", mapOf("auth_code" to code), retries = 0)
        val token = r.result.firstStr("token") ?: return false
        if (!r.success) return false
        prefs.session = token
        clearCaches()
        refreshUser()
        coroutineScope {
            async { runCatching { refreshContinueWatching() } }
            async { runCatching { refreshLibrary() } }
            async { runCatching { refreshNotifications() } }
        }
        return true
    }

    fun logout() {
        prefs.session = null
        prefs.userJson = null
        _user.value = null
        _library.value = Library()
        _continueWatching.value = emptyList()
        serverContinueWatching = emptyList()
        _local.value = emptyMap()
        localLoaded = false
        _notifications.value = NotificationsState()
        clearCaches()
    }

    fun canStream(title: Title): Boolean = title.freeStream || (isLoggedIn && _user.value?.allowedToStream == true)

    private fun clearCaches() {
        detailsCache.evictAll()
        pageCache.evictAll()
        streamCache.evictAll()
    }

    companion object {
        private const val PAGE_TTL_MS = 5 * 60_000L
        /** Local positions override the server's for a while; after that the server is the truth again. */
        private const val LOCAL_PROGRESS_TTL_MS = 3 * 24 * 60 * 60_000L
        private const val FARSI_PROGRESS_TTL_MS = 120 * 24 * 60 * 60_000L
    }
}
