package com.seenama.tv.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The domains that belong to 30nama: the API and its mirrors, its image CDN, and the
 * video/subtitle CDNs its responses point to (registered as they are seen). Only these
 * receive the device's browser user agent; every other service gets OkHttp's generic one.
 */
object ServiceHosts {
    private val domains = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
        addAll(listOf("30nama.com", "hana-api.com", "kazem-api.com"))
    }

    /** Registers the domain of [url] (e.g. a stream link from a 30nama response). */
    fun register(url: String?) {
        val host = url?.toHttpUrlOrNull()?.host ?: return
        domains += baseDomain(host)
    }

    fun isOwn(host: String): Boolean = baseDomain(host) in domains

    /** "de-stream-node.hailey-cdn.com" → "hailey-cdn.com" (last two labels). */
    private fun baseDomain(host: String): String = host.split('.').takeLast(2).joinToString(".")
}
