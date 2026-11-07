package com.seenama.tv

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Debug-only fake backend so the UI and player can be exercised without
 * access to the real API. Enable with:
 *   adb shell am start -n com.seenama.tv/.MainActivity --ez demo true
 */
object DevHooks {
    /** Fails every request while the app simulates being offline (fault-injection testing). */
    fun faultInterceptor(isOffline: () -> Boolean): Interceptor? = Interceptor { chain ->
        if (isOffline()) throw java.io.IOException("simulated offline")
        chain.proceed(chain.request())
    }

    /** Logs raw `/action/` response bodies (tag ApiRaw) so real response shapes can be inspected. */
    fun rawLogger(): Interceptor? = Interceptor { chain ->
        val resp = chain.proceed(chain.request())
        val url = chain.request().url
        android.util.Log.d("NetUA", "${url.host} <- ${chain.request().header("User-Agent")?.take(24) ?: "okhttp default"}")
        if (url.encodedPath.contains("/action/")) {
            val body = resp.peekBody(1024 * 1024).string()
            android.util.Log.d("ApiRaw", "${url.pathSegments.last()} HTTP ${resp.code} len=${body.length} type=${resp.header("Content-Type")}")
            body.chunked(3500).take(300).forEachIndexed { i, part ->
                android.util.Log.d("ApiRaw", "${url.pathSegments.last()}#$i $part")
            }
        }
        resp
    }

    fun demoInterceptor(): Interceptor = Interceptor { chain ->
        val req = chain.request()
        val url = req.url
        val body: String? = when {
            url.host == "demo.local" && url.encodedPath.endsWith(".vtt") -> vtt(url.encodedPath.contains("fa"))
            url.encodedPath.contains("/action/") -> action(url.pathSegments.last(), formParams(req))
            url.encodedPath.endsWith("sdk-endpoint.json") -> """[{"location":"WORLD","host":"https://demo.api/api/v2"}]"""
            else -> null
        }
        if (body == null) chain.proceed(req)
        else Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(body.toResponseBody(if (body.startsWith("WEBVTT")) "text/vtt".toMediaType() else "application/json".toMediaType()))
            .build()
    }

    private fun formParams(req: okhttp3.Request): Map<String, String> {
        val b = req.body as? okhttp3.FormBody ?: return emptyMap()
        return (0 until b.size).associate { b.name(it) to b.value(it) }
    }

    private var polls = 0

    private val names = listOf(
        "Dune: Part Two" to "تلماسه: قسمت دوم", "Oppenheimer" to "اوپنهایمر", "The Bear" to "خرس",
        "Shogun" to "شوگان", "Severance" to "جدایی", "Fallout" to "فال‌اوت", "Interstellar" to "میان‌ستاره‌ای",
        "Breaking Bad" to "بریکینگ بد", "Arcane" to "آرکین", "Inception" to "تلقین", "The Last of Us" to "آخرین بازمانده از ما",
        "Parasite" to "انگل", "Dark" to "تاریکی", "Spirited Away" to "شهر اشباح",
    )

    private fun post(id: Int, series: Boolean = id % 3 == 0): String {
        val (en, fa) = names[id % names.size]
        return """{"id":$id,"post_type":"${if (series) "series" else "movie"}",
          "title":{"english":"$en","local":"$fa"},
          "plot":{"english":"When a crisis upends everything they know, a group of strangers must decide how far they will go to survive — and which secrets are worth keeping.","local":"داستانی پرکشش دربارهٔ انسان‌هایی که در دل بحران راهی برای ادامه پیدا می‌کنند و رازهایی که همه‌چیز را تغییر می‌دهد."},
          "image":{"cover":{"webp":"https://picsum.photos/seed/c$id/1280/720","jpg":"https://picsum.photos/seed/c$id/1280/720"},
                   "poster":{"webp":{"large":"https://picsum.photos/seed/p$id/400/600"}}},
          "score":{"imdb":{"score":${(70 + id % 25) / 10.0}},"30nama":{"score":${(75 + id % 20) / 10.0}}},
          "info":{"year":"${2010 + id % 15}","time":{"default":${90 + id % 60}},"age":"+${if (id % 2 == 0) 13 else 17}","seasons":${if (series) 2 else 0}},
          "options":{"free_stream":${id % 7 == 0}},
          "genre":[{"name":"Drama","slug":"drama"},{"name":"Sci-Fi","slug":"sci-fi"},{"name":"Thriller","slug":"thriller"}],
          "crew":{"director":[{"name":"Denis Villeneuve"}]},
          "cast":[{"name":"Timothée Chalamet","as":"Paul","image":"https://picsum.photos/seed/a$id/200/200"},{"name":"Zendaya","as":"Chani","image":"https://picsum.photos/seed/b$id/200/200"}],
          "country":[{"name":"United States"}],"language":[{"name":"English"}]}"""
    }

    private fun posts(from: Int, n: Int) = (from until from + n).joinToString(",") { post(it) }

    private fun ok(result: String) = """{"success":true,"result":$result}"""

    private fun action(name: String, p: Map<String, String>): String = when (name) {
        "test" -> ok("true")
        "endpoint" -> ok("""[{"host":"https://demo.api/api/v2"}]""")
        "home_stream" -> ok("""{"hero_header":{"posts":[${posts(1, 4)}]},"suggested":{"posts":[${posts(10, 14)}]},
            "movies":{"posts":[${posts(30, 14)}]},"series":{"posts":[${(50 until 64).joinToString(",") { post(it, true) }}]},"anime":{"posts":[${posts(70, 10)}]}}""")
        "menu" -> ok("""{"categories":[${(1..8).joinToString(",") { """{"id":$it,"name":"Collection $it","slug":"cat-$it","image":{"webp":{"full":"https://picsum.photos/seed/s$it/600/340"}}}""" }}],
            "genres":[${listOf("Action", "Drama", "Comedy", "Horror", "Sci-Fi", "Animation").mapIndexed { i, g -> """{"id":${i + 20},"name":"$g","slug":"g-$i","image":{"webp":{"full":"https://picsum.photos/seed/g$i/600/340"}}}""" }.joinToString(",")}]}""")
        "continue_watching" -> ok("""{"posts":[${listOf(51, 3, 12).joinToString(",") { post(it).dropLast(1) + ""","data":{"season":"1","episode":"2","display_time":"24m left"}}""" }}]}""")
        "user_mylist" -> ok("""{"favorite":{"post":[3,12]},"watchlist":{"post":[51]},"notify":{"post":[]}}""")
        "user" -> ok("""{"user_id":1,"user_name":"Demo","user_type":"golden","user_allowed_to_stream":true,"user_expire":{"days":42}}""")
        "single" -> ok(post(p["post_id"]?.toIntOrNull() ?: 1))
        "search", "archive", "user_list" -> {
            val page = p["page"]?.toIntOrNull() ?: 1
            ok("""{"posts":[${posts(100 + page * 20, 20)}],"page":$page,"pages":3}""")
        }
        "qrlogin_code" -> { polls = 0; ok("""{"code":"ABCD12","url":"https://30nama.com/auth/ABCD12"}""") }
        "qrlogin_login" -> if (++polls >= 2) ok("""{"token":"demo-session"}""") else """{"success":false,"result":null}"""
        "stream" -> stream(p["post_id"]?.toIntOrNull() ?: 1)
        else -> ok("{}")
    }

    private fun stream(id: Int): String {
        val series = id % 3 == 0
        val hls = if (series) {
            (1..2).flatMap { s -> (1..6).map { e -> episode(s * 100 + e, s, e) } }.joinToString(",")
        } else episode(1, null, null)
        return ok("""{"info":{"post_id":$id,"title":{"local":"${names[id % names.size].second}","english":"${names[id % names.size].first}"}},
            "image":{"cover":{"webp":"https://picsum.photos/seed/c$id/1280/720"}},
            "continue_watching":${if (series) """{"time":95,"file_id":102}""" else "null"},
            "hls":[$hls]}""")
    }

    private fun episode(fileId: Int, season: Int?, ep: Int?): String = """{
        "info":{"file_id":$fileId,"season":${season ?: "null"},"episode":${ep ?: "null"},"episode_number":${ep ?: "null"},"episode_name":${ep?.let { "\"Chapter $it\"" } ?: "null"}},
        "options":{"intro_start":"00:00:20","intro_end":"00:00:45","previously_start":"00:00:00","previously_end":"00:00:15","end":"00:09:00","token":"t$fileId"},
        "file":{"m3u8":[
            {"label":"Auto","default":"https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_fmp4/master.m3u8"},
            {"label":"720p","default":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8","2ch":"https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"}],
          "subtitle":{"fa":"https://demo.local/sub-fa.vtt","en":"https://demo.local/sub-en.vtt"}}}"""

    private fun vtt(fa: Boolean): String = buildString {
        append("WEBVTT\n\n")
        for (i in 0 until 200) {
            val s = i * 4
            append("%02d:%02d:%02d.000 --> %02d:%02d:%02d.500\n".format(s / 3600, s / 60 % 60, s % 60, s / 3600, s / 60 % 60, s % 60 + 3))
            append(if (fa) "این زیرنویس آزمایشی شماره ${i + 1} است" else "This is test subtitle number ${i + 1}")
            append("\n\n")
        }
    }
}
