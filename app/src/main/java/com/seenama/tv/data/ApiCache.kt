package com.seenama.tv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import java.io.File

/**
 * Tiny persistent cache for API results (stale-while-revalidate): screens show
 * the last good response instantly on cold start, then refresh in the background.
 * Entries are per-account, so switching users never shows someone else's lists.
 */
class ApiCache(private val dir: File) {
    init { dir.mkdirs() }

    data class Entry(val json: JsonElement, val savedAt: Long) {
        val ageMs: Long get() = System.currentTimeMillis() - savedAt
    }

    private fun file(key: String) = File(dir, key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    suspend fun get(key: String): Entry? = withContext(Dispatchers.IO) {
        val f = file(key)
        if (!f.exists()) return@withContext null
        runCatching { Entry(AppJson.parseToJsonElement(f.readText()), f.lastModified()) }.getOrNull()
    }

    suspend fun put(key: String, json: JsonElement?) = withContext(Dispatchers.IO) {
        if (json == null) return@withContext
        runCatching {
            val tmp = File(dir, "${file(key).name}.tmp")
            tmp.writeText(json.toString())
            tmp.renameTo(file(key)) // atomic swap so a crash never leaves a half-written entry
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { dir.listFiles()?.forEach { it.delete() } }
}
