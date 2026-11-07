package com.seenama.tv.player

/** One subtitle cue; times in milliseconds. */
data class Cue(val startMs: Long, val endMs: Long, val text: String)

/**
 * Minimal WebVTT/SRT parser. The backend serves VTT, but some files are SRT
 * renamed, so both "," and "." millisecond separators are accepted.
 */
object SubtitleParser {
    private val timing = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val tags = Regex("""<[^>]+>|\{\\[^}]*\}""")

    fun parse(raw: String): List<Cue> {
        val lines = raw.replace("﻿", "").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val cues = ArrayList<Cue>(lines.size / 4)
        var i = 0
        while (i < lines.size) {
            val m = timing.find(lines[i])
            if (m == null) { i++; continue }
            val start = toMs(m.groupValues[1])
            val end = toMs(m.groupValues[2])
            i++
            val text = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (text.isNotEmpty()) text.append('\n')
                text.append(lines[i].replace(tags, "").trim())
                i++
            }
            if (text.isNotBlank()) cues += Cue(start, end, text.toString())
        }
        cues.sortBy { it.startMs }
        return cues
    }

    private fun toMs(t: String): Long {
        val (hms, frac) = t.replace(',', '.').split('.').let { it[0] to it.getOrElse(1) { "0" } }
        val parts = hms.split(':').map { it.toLong() }
        val secs = parts.fold(0L) { acc, v -> acc * 60 + v }
        return secs * 1000 + frac.padEnd(3, '0').take(3).toLong()
    }
}

/** Binary search for the cue active at [posMs]. */
fun List<Cue>.at(posMs: Long): Cue? {
    var lo = 0
    var hi = size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid].startMs <= posMs) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    if (found < 0) return null
    // Overlapping cues: walk back a little to find one still active.
    for (k in found downTo maxOf(0, found - 3)) {
        val c = this[k]
        if (posMs < c.endMs) return c
    }
    return null
}

/**
 * "HH:MM:SS" (or MM:SS / plain seconds) → milliseconds; null when blank/unparseable.
 * "00:00:00" is a real value: intros and recaps very often start at 0.
 */
fun markerToMs(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val parts = value.trim().split(':').map { it.toDoubleOrNull() ?: return null }
    return (parts.fold(0.0) { acc, v -> acc * 60 + v } * 1000).toLong()
}

/** A skippable segment exists only when both ends are present and it has a positive length. */
fun segment(start: Long?, end: Long?): LongRange? =
    if (start != null && end != null && end > start) start until end else null

/** User-adjustable subtitle look; mirrors the official app's options. */
data class SubtitleStyle(
    val language: String, // "fa", "en", "hybrid", "none"
    val size: Int,
    val color: String, // "white", "yellow"
    val background: String, // "black", "semi", "none"
) {
    fun save(prefs: com.seenama.tv.data.Prefs) {
        prefs.subtitleLanguage = language
        prefs.subtitleSize = size
        prefs.subtitleColor = color
        prefs.subtitleBg = background
    }

    companion object {
        const val MIN_SIZE = 18
        const val MAX_SIZE = 56
        fun from(prefs: com.seenama.tv.data.Prefs) =
            SubtitleStyle(prefs.subtitleLanguage, prefs.subtitleSize, prefs.subtitleColor, prefs.subtitleBg)
    }
}
