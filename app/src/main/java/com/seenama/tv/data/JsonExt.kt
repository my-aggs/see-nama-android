package com.seenama.tv.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * The backend is loose with types (numbers as strings, "" for null, objects
 * where lists are expected), so models are read through these forgiving
 * accessors instead of strict serializers.
 */
val AppJson = Json { ignoreUnknownKeys = true; isLenient = true }

fun JsonElement?.obj(): JsonObject? = this as? JsonObject

fun JsonElement?.arr(): List<JsonElement> = when (this) {
    is JsonArray -> this
    // PHP serialises sparse arrays as {"0": .., "1": ..}
    is JsonObject -> values.toList()
    else -> emptyList()
}

fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

fun JsonElement?.str(): String? = prim()?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

fun JsonElement?.int(): Int? = prim()?.let { p -> p.contentOrNull?.toDoubleOrNull()?.toInt() }

fun JsonElement?.long(): Long? = prim()?.let { p -> p.contentOrNull?.toDoubleOrNull()?.toLong() }

fun JsonElement?.double(): Double? = prim()?.let { p -> p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull() }

fun JsonElement?.bool(): Boolean = prim()?.let { p ->
    p.booleanOrNull ?: when (p.contentOrNull?.lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }
} ?: false

/** First non-blank string among several candidate paths, e.g. `j.firstStr("poster", "image.poster")`. */
fun JsonElement?.firstStr(vararg paths: String): String? {
    for (path in paths) {
        var cur: JsonElement? = this
        for (part in path.split('.')) cur = cur[part]
        cur.str()?.let { return it }
    }
    return null
}

fun JsonElement?.path(path: String): JsonElement? {
    var cur: JsonElement? = this
    for (part in path.split('.')) cur = cur[part]
    return cur
}
