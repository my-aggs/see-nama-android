package com.seenama.tv

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import com.seenama.tv.data.Api
import com.seenama.tv.data.ConnectionMonitor
import com.seenama.tv.data.ApiCache
import com.seenama.tv.data.Prefs
import com.seenama.tv.data.Repository
import com.seenama.tv.data.ServiceHosts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        // WebSettings.getDefaultUserAgent must not be called off the main thread (it blocks on it),
        // and loading WebView is slow, so resolve it once and cache it.
        if (prefs.userAgent == null) {
            prefs.userAgent = runCatching { android.webkit.WebSettings.getDefaultUserAgent(this) }.getOrNull()
        }
        // Farsihubz changes domain often: find today's from its landing page on every launch.
        scope.launch { farsi.refreshDomain() }
    }

    /** Debug builds only: serve fake data (see DevHooks). Must be set before [http] is first used. */
    var demoMode = false

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
            .cache(Cache(cacheDir.resolve("http"), 20L * 1024 * 1024))
            // 30nama's servers sit behind Cloudflare, so they get the browser user agent (like the
            // official app). Third-party services (TVmaze, Wikipedia, news) get OkHttp's generic one.
            .addInterceptor { chain ->
                val req = chain.request()
                val ua = prefs.userAgent
                val own = ServiceHosts.isOwn(req.url.host)
                chain.proceed(if (own && ua != null && req.header("User-Agent") == null) req.newBuilder().header("User-Agent", ua).build() else req)
            }
            .apply { DevHooks.faultInterceptor { connection.simulateOffline }?.let { addInterceptor(it) } }
            .apply { if (demoMode) DevHooks.demoInterceptor()?.let { addInterceptor(it) } else DevHooks.rawLogger()?.let { addInterceptor(it) } }
            .build()
    }

    /** Outlives screens; used for fire-and-forget work such as the final progress report. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val prefs: Prefs by lazy { Prefs(this) }
    val connection: ConnectionMonitor by lazy { ConnectionMonitor(this) }
    val apiCache: ApiCache by lazy { ApiCache(filesDir.resolve("api-cache")) }
    val api: Api by lazy { Api(http, prefs, connection) }
    val discovery: com.seenama.tv.data.Discovery by lazy { com.seenama.tv.data.Discovery(http, apiCache) }
    val warmer: com.seenama.tv.player.PlayerWarmer by lazy { com.seenama.tv.player.PlayerWarmer(this) }
    val subtitles: com.seenama.tv.player.SubtitleCache by lazy { com.seenama.tv.player.SubtitleCache(this) }
    val farsi: com.seenama.tv.data.FarsiCatalog by lazy { com.seenama.tv.data.FarsiCatalog(http, prefs, apiCache) }
    val repo: Repository by lazy { Repository(api, prefs, apiCache, scope, farsi) }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.25).build() }
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
            // Most TV boxes are memory-starved; RGB565 halves bitmap memory for opaque art.
            .allowRgb565(true)
            .crossfade(false)
            .build()
}

val Context.app: App get() = applicationContext as App
