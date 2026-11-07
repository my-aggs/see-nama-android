package com.seenama.tv.data

import androidx.compose.runtime.Immutable
import androidx.core.text.HtmlCompat
import kotlinx.serialization.json.JsonElement

@Immutable
data class Title(
    val id: Long,
    val postType: String?,
    val titleEn: String?,
    val titleFa: String?,
    val plot: String?,
    val poster: String?,
    val cover: String?,
    val year: String?,
    val runtimeMin: Int?,
    val age: String?,
    val seasons: Int?,
    val imdb: Double?,
    val namaScore: Double?,
    val genres: List<Genre>,
    val freeStream: Boolean,
    val seriesFlag: Boolean,
    val director: String?,
    // Continue-watching extras
    val cwSeason: String? = null,
    val cwEpisode: String? = null,
    val cwDisplayTime: String? = null,
    /** 0..1 watched fraction from continue_watching `data.progress` (a percentage). */
    val cwProgress: Float? = null,
    val imdbId: String? = null,
    val imdbVotes: Int? = null,
    val rottenTomatoes: Int? = null,
    val metacritic: Int? = null,
    val myAnimeList: Double? = null,
    /** False for download-only titles (the API mixes them into some lists). */
    val streamable: Boolean = true,
    /** "series" / "movie" / "anime" (options.title_type). */
    val titleType: String? = null,
    val airStatus: String? = null,
    val network: String? = null,
    /** e.g. "21 October 2026 Wednesday" → parsed to epoch ms. */
    val nextEpisodeMs: Long? = null,
) {
    /** Farsihubz titles are free; the FREE badge is only meaningful on 30nama titles. */
    val showFreeBadge: Boolean get() = freeStream && !FarsiIds.isFarsi(id)

    /** English title; the API appends the year ("Dune 2021"), which the metadata line already shows. */
    val displayTitle: String get() = (titleEn?.removeSuffix(" $year")?.trim() ?: titleFa) ?: ""
    val isSeries: Boolean get() = seriesFlag || (seasons ?: 0) > 0 || postType?.contains("series", true) == true

    /** Landscape art for cards and the billboard; poster as last resort. */
    val landscape: String? get() = cover ?: poster

    companion object {
        fun parse(j: JsonElement?): Title? {
            val id = j["id"].long() ?: return null
            val img = j["image"]
            val score = j["score"]
            val crew = j["crew"]
            return Title(
                id = id,
                postType = j["post_type"].str(),
                titleEn = j.firstStr("title.english"),
                titleFa = j.firstStr("title.local"),
                plot = (j.firstStr("plot.english") ?: j.firstStr("plot.local"))?.let(::decodeHtml),
                poster = img.firstStr("poster.webp.large", "poster.jpg.large", "poster.webp.medium", "poster.jpg.medium", "poster.large"),
                cover = img.firstStr("cover.webp", "cover.jpg", "cover.webp.large", "cover.jpg.large"),
                year = j.firstStr("info.year"),
                runtimeMin = j.path("info.time.default").int() ?: j.path("info.time").int(),
                age = j.firstStr("info.age"),
                seasons = j.path("info.seasons").int(),
                imdb = score.path("imdb.score").double()?.takeIf { it > 0 },
                namaScore = score.path("30nama.score").double()?.takeIf { it > 0 },
                genres = j["genre"].arr().mapNotNull { g -> g.firstStr("name")?.let { Genre(it, g.firstStr("slug")) } },
                freeStream = j.path("options.free_stream").bool(),
                seriesFlag = j.path("options.is_series").bool(),
                director = crew["creator"].arr().firstOrNull().firstStr("name")
                    ?: crew["director"].arr().firstOrNull().firstStr("name"),
                cwSeason = j.firstStr("data.season"),
                cwEpisode = j.firstStr("data.episode"),
                cwDisplayTime = j.firstStr("data.display_time"),
                cwProgress = j.path("data.progress").double()?.let { (it / 100.0).toFloat().coerceIn(0f, 1f) },
                imdbId = j.firstStr("score.imdb.link")?.let { ImdbId.find(it)?.value },
                imdbVotes = score.path("imdb.votes").int(),
                rottenTomatoes = score.path("rottentomatoes.score").int()?.takeIf { it > 0 },
                metacritic = score.path("metacritic.score").int()?.takeIf { it > 0 },
                myAnimeList = score.path("myanimelist.score").double()?.takeIf { it > 0 },
                streamable = j.path("options.stream").let { it == null || it.bool() },
                titleType = j.firstStr("options.title_type"),
                airStatus = j.firstStr("air.air_status"),
                network = j["network"].arr().firstOrNull().str(),
                nextEpisodeMs = j.firstStr("air.next_episode_date.g")?.let { g ->
                    runCatching { NextEpisodeFormat.parse(g.substringBeforeLast(' '))?.time }.getOrNull()
                },
            )
        }

        fun list(j: JsonElement?): List<Title> = j.arr().mapNotNull(::parse).distinctBy { it.id }
    }
}

@Immutable
data class Genre(val name: String, val slug: String?)

@Immutable
data class Person(val name: String, val role: String?, val image: String?)

@Immutable
data class TitleDetails(
    val title: Title,
    val cast: List<Person>,
    val countries: List<String>,
    val languages: List<String>,
) {
    companion object {
        fun parse(j: JsonElement?): TitleDetails? {
            val t = Title.parse(j) ?: return null
            return TitleDetails(
                title = t,
                cast = j["cast"].arr().mapNotNull { p ->
                    p.firstStr("name")?.let { Person(it, p.firstStr("as"), p.firstStr("image.poster.webp.medium", "image.poster.jpg.medium", "image.webp", "image")) }
                },
                countries = j["country"].arr().mapNotNull { it.firstStr("name") ?: it.str() },
                // Either a list of {name} or {primary: {name}, others: [...]}.
                languages = j["language"].obj()?.let { l -> listOfNotNull(l["primary"].firstStr("name")) + l["others"].arr().mapNotNull { it.firstStr("name") } }
                    ?: j["language"].arr().mapNotNull { it.firstStr("name") ?: it.str() },
            )
        }
    }
}

@Immutable
data class Page<T>(val items: List<T>, val page: Int, val pages: Int) {
    val isLast: Boolean get() = page >= pages || items.isEmpty()
}

@Immutable
data class Slug(val name: String, val slug: String, val image: String?, val count: Int?)

@Immutable
data class Home(
    val hero: List<Title>,
    val rows: List<Row>,
)

@Immutable
data class Row(
    val key: String,
    val title: String,
    val items: List<Title>,
    val archiveSlug: String? = null,
    /** Netflix "Top 10" style: big rank numbers next to portrait posters. */
    val ranked: Boolean = false,
    /** Portrait posters instead of landscape cards (Farsihubz movies only have posters). */
    val portrait: Boolean = false,
)

data class Library(
    val favorite: Set<Long> = emptySet(),
    val watchlist: Set<Long> = emptySet(),
    val notify: Set<Long> = emptySet(),
)

@Immutable
data class User(
    val id: Long,
    val name: String?,
    val type: String?,
    val avatar: String?,
    val expire: String?,
    val allowedToStream: Boolean,
) {
    companion object {
        fun parse(j: JsonElement?): User? {
            val id = j["user_id"].long() ?: return null
            return User(
                id = id,
                name = j["user_name"].str(),
                type = j["user_type"].str(),
                avatar = j["user_avatar"].str(),
                expire = j["user_expire"].str(),
                allowedToStream = j["user_allowed_to_stream"].bool(),
            )
        }
    }
}

// ---- Playback ----

@Immutable
data class Episode(
    val fileId: Long,
    val season: Int?,
    val episode: Int?,
    val episodeNumber: Int?,
    val name: String?,
    val token: String?,
    val introStartMs: Long?,
    val introEndMs: Long?,
    val recapStartMs: Long?,
    val recapEndMs: Long?,
    val creditsMs: Long?,
    val sources: List<Source>,
    val subtitles: Map<String, String>,
    /** Farsihubz: the episode's page, where its video link is read from just before playing. */
    val pageUrl: String? = null,
    val thumb: String? = null,
)

/** One quality ladder entry; [byAudio] maps "default" / "2ch" / "6ch" to an m3u8 URL. */
@Immutable
data class Source(val label: String, val byAudio: Map<String, String>)

@Immutable
data class StreamInfo(
    val postId: Long,
    val title: String,
    val cover: String?,
    val episodes: List<Episode>,
    val resumeFileId: Long?,
    val resumeSec: Long,
) {
    val isSeries: Boolean get() = episodes.firstOrNull()?.episode != null

    companion object {
        fun parse(j: JsonElement?): StreamInfo? {
            if (j == null) return null
            val episodes = j["hls"].arr().mapNotNull { h ->
                val fileId = h.path("info.file_id").long() ?: return@mapNotNull null
                val opt = h["options"]
                Episode(
                    fileId = fileId,
                    season = h.path("info.season").int(),
                    episode = h.path("info.episode").int(),
                    episodeNumber = h.path("info.episode_number").int(),
                    name = h.firstStr("info.episode_name")?.takeUnless { GenericEpisodeName.matches(it) },
                    token = opt.firstStr("token"),
                    introStartMs = com.seenama.tv.player.markerToMs(opt.firstStr("intro_start")),
                    introEndMs = com.seenama.tv.player.markerToMs(opt.firstStr("intro_end")),
                    recapStartMs = com.seenama.tv.player.markerToMs(opt.firstStr("previously_start")),
                    recapEndMs = com.seenama.tv.player.markerToMs(opt.firstStr("previously_end")),
                    creditsMs = com.seenama.tv.player.markerToMs(opt.firstStr("end"))?.takeIf { it > 0 },
                    sources = h.path("file.m3u8").arr().mapNotNull { s ->
                        val label = s.firstStr("label") ?: "Auto"
                        val map = buildMap {
                            s.firstStr("default")?.let { put("default", it) }
                            s.firstStr("2ch")?.let { put("2ch", it) }
                            s.firstStr("6ch")?.let { put("6ch", it) }
                        }
                        if (map.isEmpty()) null else Source(label, map)
                    },
                    subtitles = buildMap {
                        h.path("file.subtitle").obj()?.forEach { (lang, url) -> url.str()?.let { put(lang, it) } }
                    },
                )
            }
            // Video and subtitle CDNs named by 30nama count as 30nama's own servers.
            episodes.forEach { e ->
                e.sources.forEach { s -> s.byAudio.values.forEach(ServiceHosts::register) }
                e.subtitles.values.forEach(ServiceHosts::register)
            }
            return StreamInfo(
                postId = j.path("info.post_id").long() ?: 0,
                title = (j.firstStr("info.title.english", "info.title.local") ?: "").replace(TrailingYear, ""),
                cover = j.firstStr("image.cover.webp", "image.cover.jpg"),
                episodes = episodes,
                resumeFileId = j.path("continue_watching.file_id").long(),
                resumeSec = j.path("continue_watching.time").long() ?: 0,
            )
        }
    }
}

private val ImdbId = Regex("""tt\d{5,}""")
private val NextEpisodeFormat = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US)
private val GenericEpisodeName = Regex("""(?i)^\s*season\s*\d+\s*-\s*episode\s*\d+\s*$""")
private val TrailingYear = Regex("""\s+(19|20)\d{2}$""")

// ---- Notifications ----

data class NotificationsState(val items: List<AppNotification> = emptyList(), val unread: Int = 0)

@Immutable
data class AppNotification(
    val id: Long,
    val headline: String,
    val detail: String?,
    val dateMs: Long?,
    val read: Boolean,
    val post: Title?,
) {
    companion object {
        private val episodeInfo = Regex("""اپیزود\s*(\d+)\s*فصل\s*(\d+)""")
        private val dateFormat = java.text.SimpleDateFormat("d MMMM yyyy", java.util.Locale.US)

        /**
         * The backend only sends Persian copy, so English text is generated from
         * the notification type, the attached title and the episode numbers.
         */
        fun parse(j: JsonElement?): AppNotification? {
            val id = j["id"].long() ?: return null
            val post = Title.parse(j["post"])
            val raw = listOfNotNull(j["title"].str(), j["content"].str()).joinToString(" ")
            val ep = episodeInfo.find(raw)?.let { m -> "S${m.groupValues[2]}:E${m.groupValues[1]}" }
            val type = j["notification_type"].str()
            val name = post?.displayTitle
            val headline = when {
                type == "stream" && ep != null -> "New episode of ${name ?: "a show you follow"}"
                type == "stream" && post?.isSeries == true -> "New episodes of ${name ?: "a show you follow"}"
                type == "stream" -> "${name ?: "A title"} is now streaming"
                name != null -> name
                else -> decodeHtml(j["title"].str() ?: "Update")
            }
            val detail = when {
                ep != null -> "$ep is now available to stream"
                type == "stream" -> "Watch it now"
                else -> j["content"].str()?.let(::decodeHtml)
            }
            val date = j.firstStr("date.g")?.let { g ->
                runCatching { dateFormat.parse(g.substringBeforeLast(' '))?.time }.getOrNull()
            }
            return AppNotification(id, headline, detail, date, j["read"].bool(), post)
        }
    }
}

private fun decodeHtml(s: String): String =
    if ('&' in s || '<' in s) HtmlCompat.fromHtml(s, HtmlCompat.FROM_HTML_MODE_LEGACY).toString().trim() else s
