package com.seenama.tv.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.random.Random
import kotlin.coroutines.resumeWithException

class ApiException(message: String, val retryable: Boolean = true) : IOException(message)

/** Result envelope used by every action: `{success, result}`. */
data class ApiResponse(val success: Boolean, val result: JsonElement?)

/**
 * Talks to the 30nama "SDK" API: `POST {endpoint}/action/{name}` with a
 * form-encoded body. Handles mirror discovery the same way the official app
 * does (test current host → fetch mirror list from three sources → race the
 * mirrors and keep the fastest), plus:
 *  - exponential backoff with jitter, only for transient failures;
 *  - waiting for connectivity instead of failing while the device is offline;
 *  - feeding health/latency into [ConnectionMonitor] for the status badge.
 */
class Api(
    private val http: OkHttpClient,
    private val prefs: Prefs,
    private val monitor: ConnectionMonitor,
) {
    // Resolved once on the main thread in App.onCreate (WebView UA, like the official app).
    private val userAgent: String = prefs.userAgent ?: System.getProperty("http.agent") ?: "Mozilla/5.0 (Linux; Android TV)"

    @Volatile var endpoint: String = prefs.endpoint ?: DEFAULT_ENDPOINT
        private set

    private val discoverLock = Mutex()
    @Volatile private var lastDiscovery = 0L
    @Volatile private var knownMirrors: List<String> = emptyList()

    init {
        monitor.onEndpoint(endpoint)
        ServiceHosts.register(endpoint)
    }

    /**
     * @param retries extra attempts for transient failures (network errors, 5xx, Cloudflare blocks).
     * @param waitForNetworkMs how long to wait for connectivity when the device is offline.
     */
    suspend fun call(
        action: String,
        params: Map<String, Any?> = emptyMap(),
        retries: Int = 2,
        waitForNetworkMs: Long = 15_000,
    ): ApiResponse {
        var attempt = 0
        while (true) {
            if (!monitor.state.value.network && !monitor.awaitNetwork(waitForNetworkMs)) {
                monitor.onFailure()
                throw ApiException("offline")
            }
            try {
                return post(endpoint, action, params)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e is ApiException && !e.retryable) throw e
                if (attempt >= retries) {
                    // Host may have been blocked/rotated: rediscover once, then try one last time.
                    monitor.onRecovering()
                    if (rediscover()) return post(endpoint, action, params)
                    monitor.onFailure()
                    throw e
                }
                attempt++
                monitor.onRecovering()
                delay(backoffMs(attempt))
            }
        }
    }

    /** 600ms, 1.2s, 2.4s… (capped at 8s) with ±25% jitter so many TVs don't retry in lockstep. */
    private fun backoffMs(attempt: Int): Long {
        val base = (600L shl (attempt - 1).coerceAtMost(4)).coerceAtMost(8_000)
        return base + Random.nextLong(-base / 4, base / 4 + 1)
    }

    /** Returns `result` when `success`, else throws. */
    suspend fun result(action: String, params: Map<String, Any?> = emptyMap()): JsonElement? {
        val r = call(action, params)
        if (!r.success) throw ApiException("$action failed", retryable = false)
        return r.result
    }

    private suspend fun post(base: String, action: String, params: Map<String, Any?>, timeoutMs: Long? = null, track: Boolean = true): ApiResponse {
        val body = FormBody.Builder().apply {
            params.forEach { (k, v) ->
                when (v) {
                    null -> Unit
                    is Collection<*> -> v.filterNotNull().forEach { add(k, it.toString()) }
                    else -> add(k, v.toString())
                }
            }
        }.build()
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/action/$action")
            .post(body)
            .header("c-api-key", PLATFORM_KEY)
            .header("c-version-number", SDK_VERSION)
            .header("c-language", prefs.language)
            .header("user-agent", userAgent)
            .apply { prefs.session?.let { header("c-token", it) } }
            .build()
        val call = suspend {
            val started = System.nanoTime()
            http.newCall(req).await().use { resp ->
                val text = resp.body?.string().orEmpty()
                val json = text.trimStart().startsWith("{")
                when {
                    // Some actions (e.g. observer) answer 204 / an empty body on success.
                    resp.isSuccessful && text.isBlank() -> ApiResponse(true, null)
                    // Cloudflare challenge pages, gateway errors: transient, worth a retry / mirror switch.
                    !json && (resp.code >= 500 || resp.code == 403 || resp.code == 429) -> throw ApiException("HTTP ${resp.code} for $action")
                    !json && !resp.isSuccessful -> throw ApiException("HTTP ${resp.code} for $action", retryable = false)
                    else -> {
                        val j = runCatching { AppJson.parseToJsonElement(text) }.getOrNull()
                            ?: throw ApiException("Bad response for $action")
                        ApiResponse(j["success"].bool(), j["result"])
                    }
                }.also { if (track) monitor.onSuccess((System.nanoTime() - started) / 1_000_000, base) }
            }
        }
        return if (timeoutMs != null) withTimeoutOrNull(timeoutMs) { call() } ?: throw ApiException("timeout") else call()
    }

    /** Startup check: is the stored host alive? If not, find a working mirror. */
    suspend fun initialize(): Boolean {
        if (!monitor.state.value.network) monitor.awaitNetwork(10_000)
        val ok = runCatching { post(endpoint, "test", emptyMap(), 15_000).success }.getOrDefault(false)
        if (ok) return true
        monitor.onRecovering()
        return rediscover(force = true).also { if (!it) monitor.onFailure() }
    }

    private suspend fun rediscover(force: Boolean = false): Boolean = discoverLock.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastDiscovery < 30_000) return false
        lastDiscovery = now
        val hosts = mirrors(refresh = true)
        if (hosts.isEmpty()) return false
        val winner = firstNonNull(hosts.map { host ->
            suspend { host.takeIf { runCatching { post(host, "test", emptyMap(), 10_000, track = false).success }.getOrDefault(false) } }
        }) ?: return false
        Log.i(TAG, "endpoint -> $winner")
        switchTo(winner)
        true
    }

    /** All known API mirrors (from the API itself, Cloudflare R2 or ArvanCloud — whichever answers first). */
    suspend fun mirrors(refresh: Boolean = false): List<String> {
        if (!refresh && knownMirrors.isNotEmpty()) return knownMirrors
        val now = System.currentTimeMillis()
        val hosts = firstNonEmpty(
            { runCatching { post(endpoint, "endpoint", emptyMap(), 10_000, track = false).result.hosts() }.getOrDefault(emptyList()) },
            { fetchHosts("$MIRROR_CLOUDFLARE?t=$now") },
            { fetchHosts(MIRROR_ARVAN) },
        )
        hosts.forEach(ServiceHosts::register)
        if (hosts.isNotEmpty()) knownMirrors = (hosts + endpoint).distinct()
        return knownMirrors.ifEmpty { listOf(endpoint) }
    }

    /** Round-trip time of the `test` action against [host], or null if it doesn't answer. */
    suspend fun probe(host: String): Long? = runCatching {
        val started = System.nanoTime()
        val ok = post(host, "test", emptyMap(), 8_000, track = false).success
        if (ok) (System.nanoTime() - started) / 1_000_000 else null
    }.getOrNull()

    fun switchTo(host: String) {
        ServiceHosts.register(host)
        endpoint = host
        prefs.endpoint = host
        monitor.onEndpoint(host)
    }

    private suspend fun fetchHosts(url: String): List<String> = runCatching {
        http.newCall(Request.Builder().url(url).build()).await().use { r ->
            AppJson.parseToJsonElement(r.body!!.string()).hosts()
        }
    }.getOrDefault(emptyList())

    private fun JsonElement?.hosts(): List<String> = arr().mapNotNull { it.firstStr("host") ?: it.str() }

    companion object {
        private const val TAG = "Api"
        const val DEFAULT_ENDPOINT = "https://world.hana-api.com/api/v2"
        val PLATFORM_KEY: String = com.seenama.tv.BuildConfig.PLATFORM_KEY // set namaApiKey in local.properties
        const val SDK_VERSION = "1.0.0"
        const val MIRROR_CLOUDFLARE = "https://pub-ac69cf865ce145f8b4c1ed87dc2a9fb7.r2.dev/sdk-endpoint.json"
        const val MIRROR_ARVAN = "https://30update.s3.ir-thr-at1.arvanstorage.ir/sdk-endpoint.json"
    }
}

/** Runs all [blocks] concurrently and returns the first non-empty list. */
private suspend fun <T> firstNonEmpty(vararg blocks: suspend () -> List<T>): List<T> =
    firstNonNull(blocks.map { b -> suspend { b().takeIf { it.isNotEmpty() } } }) ?: emptyList()

/** Runs all [blocks] concurrently; returns the first non-null result and cancels the rest. */
private suspend fun <T : Any> firstNonNull(blocks: List<suspend () -> T?>): T? = coroutineScope {
    val winner = CompletableDeferred<T?>()
    val jobs = blocks.map { b -> launch { b()?.let { winner.complete(it) } } }
    launch { jobs.forEach { it.join() }; winner.complete(null) }
    val r = winner.await()
    coroutineContext.cancelChildren()
    r
}

private fun kotlin.coroutines.CoroutineContext.cancelChildren() =
    this[kotlinx.coroutines.Job]?.children?.forEach { it.cancel() }

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resume(response)
        override fun onFailure(call: Call, e: IOException) { if (!cont.isCancelled) cont.resumeWithException(e) }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
