package com.seenama.tv.data

import android.util.LruCache
import android.util.Xml
import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

// ---- Models ----

@Immutable
data class ShowInfo(
    val status: String?,
    val network: String?,
    val premiered: String?,
    val ended: String?,
    val rating: Double?,
    val nextEpisode: EpisodeMeta?,
    val episodes: List<EpisodeMeta>,
    val cast: List<Person>,
) {
    fun episode(season: Int?, number: Int?): EpisodeMeta? =
        episodes.firstOrNull { it.season == (season ?: 1) && it.number == number }
}

@Immutable
data class EpisodeMeta(
    val season: Int,
    val number: Int?,
    val name: String?,
    val summary: String?,
    val image: String?,
    val airdate: String?,
    val airstampMs: Long?,
    val runtimeMin: Int?,
)

@Immutable
data class WikiSummary(val extract: String, val url: String, val thumbnail: String?)

@Immutable
data class NewsItem(
    val title: String,
    val link: String,
    val summary: String?,
    val image: String?,
    val source: String,
    val dateMs: Long?,
    val author: String?,
) {
    /** Titles quoted in a headline (‘Dune’, “Severance”) — used for "Find on See-Nama". */
    val mentionedTitles: List<String>
        get() = Regex("""[‘“'"]([^’”'"]{2,60})[’”'"]""").findAll(title).map { it.groupValues[1].trim() }
            .filter { it.split(' ').size <= 8 }.distinct().toList()
}

data class NewsFeed(val name: String, val url: String)

/**
 * Free, key-less knowledge sources layered on top of the 30nama catalog:
 *  - TVmaze: episode names/synopses/stills, cast photos, airing status, next episode.
 *  - Wikidata → Wikipedia: an "About" summary, matched precisely by IMDb id.
 *  - RSS (Variety, Deadline, IndieWire): entertainment news.
 * Everything is cached in memory and on disk with a TTL, and failures are silent:
 * these are enrichments, never blockers.
 */
class Discovery(private val http: OkHttpClient, private val disk: ApiCache) {

    private val showCache = LruCache<String, ShowInfo>(32)
    private val wikiCache = LruCache<String, WikiSummary>(32)

    suspend fun show(imdbId: String): ShowInfo? {
        showCache[imdbId]?.let { return it }
        val lookup = cachedJson("tvmaze-lookup-$imdbId", 7 * DAY) { get("https://api.tvmaze.com/lookup/shows?imdb=$imdbId") } ?: return null
        val id = lookup["id"].long() ?: return null
        val j = cachedJson("tvmaze-show-$id", 12 * HOUR) {
            get("https://api.tvmaze.com/shows/$id?embed%5B%5D=episodes&embed%5B%5D=cast&embed%5B%5D=nextepisode")
        } ?: return null
        val emb = j["_embedded"]
        return ShowInfo(
            status = j["status"].str(),
            network = j.firstStr("network.name", "webChannel.name"),
            premiered = j["premiered"].str(),
            ended = j["ended"].str(),
            rating = j.path("rating.average").double(),
            nextEpisode = emb["nextepisode"]?.let(::episodeMeta),
            episodes = emb["episodes"].arr().mapNotNull(::episodeMeta),
            cast = emb["cast"].arr().mapNotNull { c ->
                val name = c.firstStr("person.name") ?: return@mapNotNull null
                Person(name, c.firstStr("character.name"), c.firstStr("person.image.medium", "character.image.medium"))
            },
        ).also { showCache.put(imdbId, it) }
    }

    private fun episodeMeta(e: JsonElement?): EpisodeMeta? {
        val season = e["season"].int() ?: return null
        return EpisodeMeta(
            season = season,
            number = e["number"].int(),
            name = e["name"].str(),
            summary = e["summary"].str()?.let(::stripHtml),
            image = e.firstStr("image.original", "image.medium"),
            airdate = e["airdate"].str(),
            airstampMs = e["airstamp"].str()?.let { runCatching { java.time.OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() },
            runtimeMin = e["runtime"].int(),
        )
    }

    suspend fun wiki(imdbId: String): WikiSummary? {
        wikiCache[imdbId]?.let { return it }
        val title = cachedJson("wiki-title-$imdbId", 30 * DAY) {
            // Wikidata property P345 = IMDb id → exact entity → English Wikipedia article.
            val search = get("https://www.wikidata.org/w/api.php?action=query&list=search&srsearch=haswbstatement:P345=$imdbId&format=json")
            val q = search.path("query.search").arr().firstOrNull()["title"].str() ?: return@cachedJson null
            val entity = get("https://www.wikidata.org/wiki/Special:EntityData/$q.json")
            entity.path("entities.$q.sitelinks.enwiki.title")?.let { kotlinx.serialization.json.JsonPrimitive(it.str()) }
        }?.str() ?: return null
        val j = cachedJson("wiki-summary-$imdbId", 7 * DAY) {
            get("https://en.wikipedia.org/api/rest_v1/page/summary/" + java.net.URLEncoder.encode(title.replace(' ', '_'), "UTF-8").replace("+", "_"))
        } ?: return null
        val extract = j["extract"].str() ?: return null
        return WikiSummary(
            extract = extract,
            url = j.firstStr("content_urls.desktop.page") ?: "https://en.wikipedia.org/wiki/${title.replace(' ', '_')}",
            thumbnail = j.firstStr("originalimage.source", "thumbnail.source"),
        ).also { wikiCache.put(imdbId, it) }
    }

    // ---- News ----

    val feeds = listOf(
        NewsFeed("Variety Film", "https://variety.com/v/film/feed/"),
        NewsFeed("Variety TV", "https://variety.com/v/tv/feed/"),
        NewsFeed("Deadline", "https://deadline.com/feed/"),
        NewsFeed("IndieWire", "https://www.indiewire.com/feed/"),
    )

    suspend fun news(): List<NewsItem> = coroutineScope {
        feeds.map { f -> async { runCatching { feed(f) }.getOrDefault(emptyList()) } }.awaitAll()
            .flatten()
            .distinctBy { it.link }
            .sortedByDescending { it.dateMs ?: 0 }
    }

    private suspend fun feed(f: NewsFeed): List<NewsItem> {
        val key = "news-" + f.url.hashCode()
        val cached = disk.get(key)
        val xml = if (cached != null && cached.ageMs < 30 * MINUTE) cached.json.str()
        else runCatching { getText(f.url) }.getOrNull()?.also { disk.put(key, kotlinx.serialization.json.JsonPrimitive(it)) }
            ?: cached?.json.str()
        return xml?.let { parseRss(it, f.name) }.orEmpty()
    }

    private fun parseRss(xml: String, source: String): List<NewsItem> {
        val out = mutableListOf<NewsItem>()
        val p = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(xml))
        }
        var inItem = false
        var title = ""; var link = ""; var desc: String? = null; var content: String? = null
        var image: String? = null; var date: Long? = null; var author: String? = null
        while (runCatching { p.next() }.getOrDefault(XmlPullParser.END_DOCUMENT) != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "item" -> { inItem = true; title = ""; link = ""; desc = null; content = null; image = null; date = null; author = null }
                    "title" -> if (inItem) title = p.nextText()
                    "link" -> if (inItem) link = p.nextText()
                    "description" -> if (inItem) desc = p.nextText()
                    "content:encoded" -> if (inItem) content = p.nextText()
                    "pubDate" -> if (inItem) date = rfc822(p.nextText())
                    "dc:creator" -> if (inItem) author = p.nextText()
                    "media:content", "media:thumbnail", "enclosure" ->
                        if (inItem && image == null) image = p.getAttributeValue(null, "url")?.takeIf { p.name != "enclosure" || p.getAttributeValue(null, "type")?.startsWith("image") == true }
                }
                XmlPullParser.END_TAG -> if (p.name == "item" && inItem) {
                    inItem = false
                    val img = image ?: (content ?: desc)?.let { Regex("""<img[^>]+src=["']([^"']+)["']""").find(it)?.groupValues?.get(1) }
                    if (title.isNotBlank() && link.isNotBlank()) {
                        out += NewsItem(
                            title = stripHtml(title),
                            link = link.trim(),
                            summary = desc?.let(::stripHtml)?.substringBefore("The post ")?.trim()?.takeIf { it.isNotBlank() },
                            image = img,
                            source = source,
                            dateMs = date,
                            author = author?.let(::stripHtml),
                        )
                    }
                }
            }
        }
        return out
    }

    // ---- plumbing ----

    private suspend fun cachedJson(key: String, ttlMs: Long, fetch: suspend () -> JsonElement?): JsonElement? {
        val cached = disk.get(key)
        if (cached != null && cached.ageMs < ttlMs) return cached.json
        val fresh = runCatching { fetch() }.getOrNull()
        if (fresh != null && fresh !is kotlinx.serialization.json.JsonNull) { disk.put(key, fresh); return fresh }
        return cached?.json // stale is better than nothing when offline
    }

    private suspend fun get(url: String): JsonElement = AppJson.parseToJsonElement(getText(url))

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("Accept", "*/*").build()).await().use { r ->
            if (!r.isSuccessful) throw ApiException("HTTP ${r.code}")
            r.body!!.string()
        }
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
        private val rfcFormat = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US)
        private fun rfc822(s: String): Long? = runCatching { synchronized(rfcFormat) { rfcFormat.parse(s.trim())?.time } }.getOrNull()
    }
}

fun stripHtml(s: String): String = HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().replace('￼', ' ').trim()
