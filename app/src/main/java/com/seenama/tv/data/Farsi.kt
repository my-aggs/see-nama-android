package com.seenama.tv.data

import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Farsihubz (farsihubby.com): a free WordPress site with Iranian films, series and live channels.
 *
 * Its titles live in the same id space as 30nama's, offset so they can never collide, which lets
 * the details screen, player, Continue Watching and search treat them like any other title.
 * The catalog comes from the site's public WordPress REST API; seasons, episodes and video links
 * only exist in the HTML pages, so those are scraped. The landing domain points at the real site,
 * which moves often, so it's looked up again from the landing page on every launch (and again whenever
 * it stops answering); the last known domain is only a fallback for when the landing page is down.
 */
object FarsiIds {
    const val MOVIE = 8_000_000_000L
    const val SHOW = 8_500_000_000L
    private const val END = 9_000_000_000L

    fun isFarsi(id: Long) = id in MOVIE until END
    fun isShow(id: Long) = id in SHOW until END
    fun wpId(id: Long) = if (isShow(id)) id - SHOW else id - MOVIE
}

val Title.isFarsi: Boolean get() = FarsiIds.isFarsi(id)

@Immutable
data class LiveChannel(val id: Long, val name: String, val logo: String?, val pageUrl: String, val about: String?)

/** What a page's player points at: a playable stream, or something only YouTube can open. */
sealed interface FarsiVideo {
    data class Stream(val urls: List<String>) : FarsiVideo
    data class YouTube(val url: String) : FarsiVideo
}

class FarsiCatalog(
    private val http: OkHttpClient,
    private val prefs: Prefs,
    private val disk: ApiCache,
) {
    private val hostLock = Mutex()
    /** The site's domain as discovered during this launch (never trusted from a previous one). */
    @Volatile private var host: String? = null

    private val pageCache = LruCache<String, Pair<Long, Page<Title>>>(40)
    private val titles = LruCache<Long, Pair<TitleDetails, String>>(200) // details + page URL
    private val streams = LruCache<Long, Pair<Long, StreamInfo>>(12)
    private val videos = LruCache<String, Pair<Long, FarsiVideo>>(64)
    private var genres: Map<String, Map<Long, String>>? = null

    // ---- Networking ----

    private suspend fun base(): String = host ?: discover()

    /** Called at app start so the current domain is known before the Farsi tab is opened. */
    suspend fun refreshDomain() { runCatching { base() } }

    /** Finds the real site's domain from the landing page's "enter the site" links. */
    private suspend fun discover(): String = hostLock.withLock {
        host?.let { return it }
        val fromLanding = Landings.firstNotNullOfOrNull { landing ->
            runCatching {
                HostLink.findAll(fetchText(landing)).map { it.groupValues[1] }.firstOrNull { !it.contains("farsihub") }
            }.getOrNull()
        }
        if (fromLanding != null) prefs.farsiHost = fromLanding
        val found = fromLanding ?: prefs.farsiHost ?: DEFAULT_HOST
        android.util.Log.i("Farsi", "site domain: $found" + if (fromLanding == null) " (landing page unreachable, using last known)" else "")
        host = found
        found
    }

    /**
     * Images in cached responses point at whatever domain was current when they were saved;
     * WordPress paths don't change, so they're moved to the current (or last known) domain.
     */
    private fun onCurrentHost(url: String?): String? {
        if (url == null || "/wp-content/" !in url) return url
        val h = host ?: prefs.farsiHost ?: return url
        return "https://$h/" + url.substringAfter("://").substringAfter('/')
    }

    private suspend fun rediscover() {
        hostLock.withLock { host = null }
        discover()
    }

    private suspend fun fetchText(url: String, referer: String? = null): String = withContext(Dispatchers.IO) {
        var last: IOException? = null
        repeat(3) { attempt ->
            try {
                val req = Request.Builder().url(url).header("User-Agent", prefs.userAgent ?: BrowserUa)
                    .apply { referer?.let { header("Referer", it) } }.build()
                http.newCall(req).await().use { r ->
                    if (r.isSuccessful) return@withContext r.body!!.string()
                    if (r.code in 400..499 && r.code != 429) throw IOException("HTTP ${r.code}")
                    last = IOException("HTTP ${r.code}")
                }
            } catch (e: IOException) {
                if (e.message?.startsWith("HTTP 4") == true) throw e
                last = e
            }
            delay(600L * (attempt + 1))
        }
        throw last ?: IOException("failed")
    }

    /** GET on the current site; if the site has moved, finds it again and retries once. */
    private suspend fun site(path: String): String {
        val first = base()
        return try {
            fetchText("https://$first$path")
        } catch (e: IOException) {
            rediscover()
            val second = base()
            if (second == first) throw e
            fetchText("https://$second$path")
        }
    }

    private suspend fun rest(path: String): JsonElement =
        withContext(Dispatchers.Default) { AppJson.parseToJsonElement(site("/wp-json/wp/v2/$path")) }

    // ---- Catalog ----

    private suspend fun genreNames(): Map<String, Map<Long, String>> {
        genres?.let { return it }
        val loaded = coroutineScope {
            listOf("movie_genre", "tv_show_genre").map { tax ->
                async {
                    tax to runCatching {
                        rest("$tax?per_page=100&_fields=id,name,count").arr()
                            .mapNotNull { g -> g["id"].long()?.let { id -> id to genreLabel(g["name"].str() ?: "") } }.toMap()
                    }.getOrDefault(emptyMap())
                }
            }.map { it.await() }.toMap()
        }
        if (loaded.values.any { it.isNotEmpty() }) genres = loaded
        return loaded
    }

    /** Genres worth a row or a filter chip, biggest first. */
    suspend fun genreList(show: Boolean, cachedOnly: Boolean = false): List<Slug> {
        val tax = if (show) "tv_show_genre" else "movie_genre"
        val diskKey = "farsi-genres-$tax"
        val json = if (cachedOnly) disk.get(diskKey)?.json ?: return emptyList()
        else try { rest("$tax?per_page=100&_fields=id,name,count").also { disk.put(diskKey, it) } } catch (e: IOException) { disk.get(diskKey)?.json ?: throw e }
        val r = json.arr()
        return r.mapNotNull { g ->
            val id = g["id"].long() ?: return@mapNotNull null
            val count = g["count"].int() ?: 0
            if (count < 3) return@mapNotNull null
            Slug(genreLabel(g["name"].str() ?: return@mapNotNull null), "farsi:$tax:$id", null, count)
        }.groupBy { it.name }.map { (_, same) -> same.maxBy { it.count ?: 0 } }.sortedByDescending { it.count }
    }

    /**
     * One page of titles. [slug] is `farsi:movies`, `farsi:shows`, `farsi:movie_genre:<id>` or
     * `farsi:tv_show_genre:<id>`. Pages are cached briefly in memory and the first page of each
     * list on disk, so rows show instantly on a cold start and survive the site being down.
     */
    suspend fun page(slug: String, page: Int, query: String? = null, perPage: Int = 24): Page<Title> {
        val key = "$slug:$page:${query.orEmpty()}:$perPage"
        pageCache[key]?.let { (at, p) -> if (System.currentTimeMillis() - at < PAGE_TTL_MS) return p }
        val parts = slug.removePrefix("farsi:").split(':')
        val (type, filter) = when (parts[0]) {
            "movies" -> "movie" to ""
            "shows" -> "tv_show" to ""
            "movie_genre" -> "movie" to "&movie_genre=${parts[1]}"
            "tv_show_genre" -> "tv_show" to "&tv_show_genre=${parts[1]}"
            else -> throw IllegalArgumentException(slug)
        }
        val search = query?.let { "&search=" + java.net.URLEncoder.encode(it, "UTF-8") }.orEmpty()
        val order = if (type == "tv_show" && query == null) "&orderby=modified" else ""
        val diskKey = "farsi-$key"
        val items = try {
            val json = rest("$type?per_page=$perPage&page=$page$filter$search$order&_fields=$FIELDS")
            if (page == 1) disk.put(diskKey, json)
            parseList(json, show = type == "tv_show")
        } catch (e: IOException) {
            // Past the last page WordPress answers 400; anything else falls back to the last good copy.
            if (e.message == "HTTP 400") emptyList()
            else disk.get(diskKey)?.json?.let { parseList(it, type == "tv_show") } ?: throw e
        }
        val result = Page(items, page, if (items.size < perPage) page else page + 1)
        pageCache.put(key, System.currentTimeMillis() to result)
        return result
    }

    /** The last good first page of a list, from disk (for an instant first paint). */
    suspend fun cachedPage(slug: String, perPage: Int): Page<Title>? {
        val json = disk.get("farsi-$slug:1::$perPage")?.json ?: return null
        val items = parseList(json, show = slug.startsWith("farsi:shows") || slug.startsWith("farsi:tv_show"))
        return Page(items, 1, 2)
    }

    suspend fun search(query: String): List<Title> = coroutineScope {
        val m = async { runCatching { page("farsi:movies", 1, query, 20).items }.getOrDefault(emptyList()) }
        val s = async { runCatching { page("farsi:shows", 1, query, 20).items }.getOrDefault(emptyList()) }
        s.await() + m.await()
    }

    private suspend fun parseList(json: JsonElement, show: Boolean): List<Title> {
        val names = genreNames()[if (show) "tv_show_genre" else "movie_genre"].orEmpty()
        return json.arr().mapNotNull { j -> parseTitle(j, show, names) }
    }

    private fun parseTitle(j: JsonElement, show: Boolean, genreNames: Map<Long, String>): Title? {
        val wp = j["id"].long() ?: return null
        val name = decode(j.firstStr("title.rendered") ?: return null)
            .replace(SeriesSuffix, "").trim()
        val content = j.firstStr("content.rendered").orEmpty()
        val text = plain(content)
        val image = j.path("yoast_head_json.og_image").arr().firstOrNull()
        val img = onCurrentHost(image.firstStr("url"))
        val portrait = (image["height"].int() ?: 0) > (image["width"].int() ?: 1)
        val id = (if (show) FarsiIds.SHOW else FarsiIds.MOVIE) + wp
        val genreIds = j[if (show) "tv_show_genre" else "movie_genre"].arr().mapNotNull { it.long() }
        val t = Title(
            id = id,
            postType = if (show) "series" else "movie",
            titleEn = name,
            titleFa = PersianName.find(text)?.groupValues?.get(1)?.trim()?.removePrefix("فیلم ")?.removePrefix("سریال ")?.trim(),
            plot = text.takeIf { it.isNotBlank() },
            poster = img.takeIf { portrait },
            cover = img.takeIf { !portrait },
            year = (ReleasedIn.find(text) ?: AnyYear.find(text))?.groupValues?.get(1),
            runtimeMin = null,
            age = null,
            seasons = null,
            imdb = null,
            namaScore = null,
            genres = genreIds.mapNotNull { gid -> genreNames[gid]?.let { Genre(it, "farsi:${if (show) "tv_show_genre" else "movie_genre"}:$gid") } }
                .distinctBy { it.name },
            freeStream = true,
            seriesFlag = show,
            director = Director.find(text)?.groupValues?.get(1)?.trim(),
            titleType = if (show) "series" else "movie",
        )
        val cast = Starring.find(text)?.groupValues?.get(1)
            ?.split(Regex(""",\s*|\s+and\s+"""))?.map { it.trim().trimEnd('.') }
            ?.filter { it.length in 3..40 && it.first().isUpperCase() }?.take(10)
            ?.map { Person(it, null, null) }.orEmpty()
        val details = TitleDetails(t, cast, listOf("Iran"), listOf("Persian"))
        j.firstStr("link")?.let { titles.put(id, details to it) }
        return t
    }

    suspend fun details(id: Long): TitleDetails {
        titles[id]?.let { return it.first }
        val show = FarsiIds.isShow(id)
        val type = if (show) "tv_show" else "movie"
        val diskKey = "farsi-title-$id"
        val json = try {
            rest("$type/${FarsiIds.wpId(id)}?_fields=$FIELDS").also { disk.put(diskKey, it) }
        } catch (e: IOException) {
            disk.get(diskKey)?.json ?: throw e
        }
        val names = genreNames()[if (show) "tv_show_genre" else "movie_genre"].orEmpty()
        parseTitle(json, show, names) ?: throw ApiException("not found", retryable = false)
        return titles[id]?.first ?: throw ApiException("not found", retryable = false)
    }

    private suspend fun pageUrl(id: Long): String {
        titles[id]?.let { return it.second }
        details(id)
        return titles[id]?.second ?: throw ApiException("no page", retryable = false)
    }

    /** Points a stored page URL at the current domain (the site moves; paths stay the same). */
    private suspend fun current(url: String): String {
        val path = url.substringAfter("://").substringAfter('/', "")
        return "https://${base()}/$path"
    }

    private suspend fun html(url: String): String {
        val u = current(url)
        return try { fetchText(u) } catch (e: IOException) { rediscover(); fetchText(current(url)) }
    }

    // ---- Episodes & video ----

    /**
     * Movies are a single "episode". Series list their seasons and episodes on the show page;
     * each episode's video link is only on its own page, so it's resolved when it's played.
     */
    suspend fun stream(id: Long, fresh: Boolean = false): StreamInfo {
        if (!fresh) streams[id]?.let { (at, s) -> if (System.currentTimeMillis() - at < STREAM_TTL_MS) return s }
        val d = details(id)
        val t = d.title
        val url = pageUrl(id)
        val info = if (!FarsiIds.isShow(id)) {
            StreamInfo(id, t.displayTitle, t.landscape, listOf(
                Episode(FarsiIds.wpId(id), null, null, null, t.displayTitle, null, null, null, null, null, null, emptyList(), emptyMap(), pageUrl = url),
            ), null, 0)
        } else {
            val html = withContext(Dispatchers.Default) { html(url) }
            val episodes = withContext(Dispatchers.Default) { parseEpisodes(html) }
            if (episodes.isEmpty()) throw ApiException("no episodes", retryable = false)
            StreamInfo(id, t.displayTitle, t.landscape, episodes, null, 0)
        }
        streams.put(id, System.currentTimeMillis() to info)
        return info
    }

    private fun parseEpisodes(html: String): List<Episode> {
        val tabsAt = html.indexOf("masvideos-tabs")
        val seasons: List<Pair<Int, String>> = if (tabsAt >= 0) {
            val labels = SeasonTab.findAll(html, tabsAt).map { it.groupValues[1] to it.groupValues[2].trim() }.toList()
            labels.mapIndexedNotNull { i, (tabId, label) ->
                val start = html.indexOf("id=\"$tabId\"").takeIf { it >= 0 } ?: return@mapIndexedNotNull null
                val next = labels.getOrNull(i + 1)?.let { html.indexOf("id=\"${it.first}\"", start) }?.takeIf { it > 0 } ?: html.length
                val number = Regex("""\d+""").find(label)?.value?.toIntOrNull() ?: (i + 1)
                number to html.substring(start, next)
            }
        } else listOf(1 to html)
        val seen = HashSet<Long>()
        return seasons.flatMap { (season, block) ->
            EpisodeBlock.findAll(block).mapNotNull { m ->
                val wp = m.groupValues[1].toLong()
                if (!seen.add(wp)) return@mapNotNull null
                val body = m.groupValues[2]
                val link = Regex("""href="([^"]+/episode/[^"]+)"""").find(body)?.groupValues?.get(1) ?: return@mapNotNull null
                val thumb = onCurrentHost(Regex("""<img[^>]+src="([^"]+)"""").find(body)?.groupValues?.get(1))
                val name = Regex("""episode__title">([^<]+)<""").find(body)?.groupValues?.get(1)?.let(::decode)?.trim()
                Triple(wp, link, name) to thumb
            }.toList().mapIndexed { i, (e, thumb) ->
                val (wp, link, name) = e
                val number = name?.let { EpisodeNumber.find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: (i + 1)
                Episode(wp, season, number, number, name, null, null, null, null, null, null, emptyList(), emptyMap(), pageUrl = link, thumb = thumb)
            }.sortedBy { it.episode }
        }
    }

    /** Fills in an episode's video link from its page. */
    suspend fun resolve(e: Episode): FarsiVideo {
        val url = e.pageUrl ?: throw ApiException("no page", retryable = false)
        videos[url]?.let { (at, v) -> if (System.currentTimeMillis() - at < STREAM_TTL_MS) return v }
        val v = videoIn(html(url)) ?: throw ApiException("no video", retryable = false)
        videos.put(url, System.currentTimeMillis() to v)
        return v
    }

    private suspend fun videoIn(html: String): FarsiVideo? {
        VideoSource.find(html)?.let { return FarsiVideo.Stream(unwrap(decode(it.groupValues[1]))) }
        val frame = IFrame.find(html)?.groupValues?.get(1)?.let(::decode) ?: return null
        return when {
            "youtube.com" in frame || "youtu.be" in frame -> FarsiVideo.YouTube(
                YouTubeId.find(frame)?.let { "https://www.youtube.com/watch?v=${it.groupValues[1]}" } ?: frame
            )
            else -> streamsFor(frame).takeIf { it.isNotEmpty() }?.let { FarsiVideo.Stream(it) }
        }
    }

    /**
     * Browser players wrap streams in CORS proxies / player pages (`…?url=<stream>`). A TV app needs no
     * CORS, so the stream itself comes first and the wrapper is kept only as a fallback.
     */
    private fun unwrap(url: String): List<String> {
        val inner = Regex("""[?&]url=([^&]+)""").find(url)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }?.takeIf { it.startsWith("http") && ".m3u8" in it }
        return if (inner != null) listOf(inner, url) else listOf(url)
    }

    /** Candidate stream URLs behind an embedded player, best first. */
    private suspend fun streamsFor(frame: String, depth: Int = 0, referer: String? = null): List<String> {
        val inner = Regex("""[?&]url=([^&]+)""").find(frame)?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
        return when {
            frame.contains(".m3u8") || (inner != null && inner.contains(".m3u8")) -> unwrap(frame)
            // Embed pages: the stream is in the page itself or in a player page nested inside it.
            "parsatv.com" in frame && depth < 2 -> {
                val page = runCatching { fetchText(frame, referer = referer ?: "https://${base()}/") }.getOrNull() ?: return emptyList()
                M3u8.findAll(page).map { it.value }.distinct().toList().ifEmpty {
                    IFrame.findAll(page).map { it.groupValues[1] }.firstOrNull { "googletagmanager" !in it }
                        ?.let { nested -> streamsFor(java.net.URI(frame).resolve(decode(nested)).toString(), depth + 1, referer = frame) }
                        .orEmpty()
                }
            }
            else -> emptyList()
        }
    }

    // ---- Live TV ----

    @Volatile private var live: Pair<Long, List<LiveChannel>>? = null

    suspend fun liveChannels(): List<LiveChannel> {
        live?.let { (at, list) -> if (System.currentTimeMillis() - at < STREAM_TTL_MS) return list }
        return loadLive().also { if (it.isNotEmpty()) live = System.currentTimeMillis() to it }
    }

    private suspend fun loadLive(): List<LiveChannel> {
        val diskKey = "farsi-live"
        val json = try {
            rest("video?per_page=50&_fields=id,title,link,content,yoast_head_json.og_image").also { disk.put(diskKey, it) }
        } catch (e: IOException) {
            disk.get(diskKey)?.json ?: throw e
        }
        return json.arr().mapNotNull { j ->
            val name = decode(j.firstStr("title.rendered") ?: return@mapNotNull null)
            LiveChannel(
                id = j["id"].long() ?: return@mapNotNull null,
                name = name,
                logo = onCurrentHost(j.path("yoast_head_json.og_image").arr().firstOrNull().firstStr("url")),
                pageUrl = j.firstStr("link") ?: return@mapNotNull null,
                about = plain(j.firstStr("content.rendered").orEmpty()).substringBefore(". ").take(160).ifBlank { null },
            )
        }.sortedBy { LiveOrder.indexOf(it.name).let { i -> if (i < 0) 99 else i } }
    }

    suspend fun liveStreams(c: LiveChannel): List<String> {
        videos[c.pageUrl]?.let { (at, v) -> if (System.currentTimeMillis() - at < STREAM_TTL_MS) return (v as? FarsiVideo.Stream)?.urls.orEmpty() }
        val v = videoIn(html(c.pageUrl))
        if (v != null) videos.put(c.pageUrl, System.currentTimeMillis() to v)
        return (v as? FarsiVideo.Stream)?.urls.orEmpty()
    }

    // ---- Recommendations ----

    suspend fun moreLikeThis(t: Title): List<Title> {
        val genre = t.genres.firstOrNull()?.slug
        val list = runCatching { page(genre ?: if (t.isSeries) "farsi:shows" else "farsi:movies", 1, perPage = 30).items }.getOrDefault(emptyList())
        return list.filter { it.id != t.id }.take(20)
    }

    fun clear() {
        pageCache.evictAll(); streams.evictAll(); videos.evictAll()
    }

    companion object {
        /** farsihub.com redirects to the current landing page; farsihubby.com is that page today. */
        private val Landings = listOf("https://farsihub.com/", "https://farsihubby.com/")
        private const val DEFAULT_HOST = "monclerjakkejacket.com"
        private const val PAGE_TTL_MS = 10 * 60_000L
        private const val STREAM_TTL_MS = 30 * 60_000L
        private const val FIELDS = "id,title,link,content,movie_genre,tv_show_genre,yoast_head_json.og_image"
        private const val BrowserUa = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36"

        private val HostLink = Regex("""href="https://([a-z0-9.-]+)/(?:movies|serials|farshub)""")
        private val SeasonTab = Regex("""href="#(tab-[^"]+)"[^>]*>\s*([^<]+?)\s*</a>""")
        private val EpisodeBlock = Regex("""<div class="post-(\d+) episode[^"]*">(.*?)</h3>""", RegexOption.DOT_MATCHES_ALL)
        private val EpisodeNumber = Regex("""(?i)(?:part|episode|ghesmate|qesmat|ep)\s*(\d+)""")
        private val VideoSource = Regex("""<source src="([^"]+)"[^>]*type="(?:video|application)/""")
        private val YouTubeId = Regex("""(?:embed/|v=|youtu\.be/)([\w-]{6,})""")
        private val IFrame = Regex("""<iframe[^>]+src=['"]([^'"]+)['"]""")
        private val M3u8 = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
        private val SeriesSuffix = Regex("""(?i)\s+(serial|series)$""")
        private val PersianName = Regex("""\(([؀-ۿ‌ ]{3,})\)""")
        private val ReleasedIn = Regex("""(?i)(?:released|produced|made|premiered|aired)[^.]{0,20}?\b((?:19|20)\d{2})\b""")
        private val AnyYear = Regex("""\b(19[4-9]\d|20[0-3]\d)\b""")
        private val Director = Regex("""directed by ([A-Z][\w'-]+(?: [A-Z][\w'-]+){0,3})""")
        private val Starring = Regex("""(?:Featuring|featuring|Starring|starring|stars|cast includes)\s+([^.;]+)""")
        private val LiveOrder = listOf("BBC Farsi", "Iran International", "Voice Of America", "MBC Persia", "Gem TV", "Tapesh Tv",
            "Shabake 1", "Shabake 2", "Shabake 3", "Shabake 4", "Shabake 5", "Shabake Khabar", "Shabake Varzesh", "Shabake Nasim")

        private val GenreNames = mapOf(
            "ejtemaei" to "Social", "khanevadegi" to "Family", "ghadimi" to "Classics", "asheghaneh" to "Romance",
            "romantic" to "Romance", "jangi" to "War", "tarikhi" to "Historical", "tarsnak" to "Horror",
            "mostanad" to "Documentary", "adventures" to "Adventure", "mystrey" to "Mystery", "mystry" to "Mystery",
            "thriller" to "Thriller", "short movie" to "Short Film", "دوبلهفارسی" to "Persian Dubbed", "علمیتخیلی" to "Sci-Fi",
        )

        fun genreLabel(raw: String): String = decode(raw).let { GenreNames[it.lowercase()] ?: it }

        private fun decode(s: String): String =
            if ('&' in s) HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY).toString() else s

        private fun plain(html: String): String =
            HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_COMPACT).toString().replace(Regex("""\s+"""), " ").trim()
    }
}
