package com.seenama.tv.data

import android.content.Context
import androidx.core.content.edit

/** Small synchronous key/value store; everything here is read on the hot path (request headers). */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("seenama", Context.MODE_PRIVATE)

    var endpoint: String?
        get() = sp.getString("endpoint", null)
        set(v) = sp.edit { putString("endpoint", v) }

    var session: String?
        get() = sp.getString("session", null)
        set(v) = sp.edit { putString("session", v) }

    var userJson: String?
        get() = sp.getString("user", null)
        set(v) = sp.edit { putString("user", v) }

    /** User chose "Continue as guest"; skip the login screen on later launches. */
    var guest: Boolean
        get() = sp.getBoolean("guest", false)
        set(v) = sp.edit { putBoolean("guest", v) }

    /** Netflix-style: start the next episode automatically after the countdown. */
    var autoplayNext: Boolean
        get() = sp.getBoolean("autoplayNext", true)
        set(v) = sp.edit { putBoolean("autoplayNext", v) }

    /** Skip intros/recaps automatically instead of showing the button. */
    var autoSkipIntro: Boolean
        get() = sp.getBoolean("autoSkipIntro", false)
        set(v) = sp.edit { putBoolean("autoSkipIntro", v) }

    var recentSearches: List<String>
        get() = sp.getString("recentSearches", "")!!.split('\n').filter { it.isNotBlank() }
        set(v) = sp.edit { putString("recentSearches", v.take(8).joinToString("\n")) }

    var userAgent: String?
        get() = sp.getString("userAgent", null)
        set(v) = sp.edit { putString("userAgent", v) }

    /** Current Farsihubz domain (the site moves; rediscovered from its landing page). */
    var farsiHost: String?
        get() = sp.getString("farsiHost", null)
        set(v) = sp.edit { putString("farsiHost", v) }

    var language: String
        get() = sp.getString("language", "en") ?: "en"
        set(v) = sp.edit { putString("language", v) }

    // Player preferences
    /**
     * Preferred quality. Only honoured when the user picked it themselves ([qualityChosen]);
     * an older build could store a fallback here by mistake, so otherwise it's "Auto" (adaptive).
     */
    var quality: String
        get() = if (qualityChosen) sp.getString("quality", "Auto") ?: "Auto" else "Auto"
        set(v) = sp.edit { putString("quality", v) }

    var qualityChosen: Boolean
        get() = sp.getBoolean("qualityChosen", false)
        set(v) = sp.edit { putBoolean("qualityChosen", v) }

    var audioChannels: String
        get() = sp.getString("audioChannels", "default") ?: "default"
        set(v) = sp.edit { putString("audioChannels", v) }

    var audioLanguage: String?
        get() = sp.getString("audioLanguage", null)
        set(v) = sp.edit { putString("audioLanguage", v) }

    /** "fa", "en", "hybrid" or "none". */
    var subtitleLanguage: String
        get() = sp.getString("subLang", "fa") ?: "fa"
        set(v) = sp.edit { putString("subLang", v) }

    var subtitleSize: Int
        get() = sp.getInt("subSize", 32)
        set(v) = sp.edit { putInt("subSize", v) }

    /** "white" or "yellow". */
    var subtitleColor: String
        get() = sp.getString("subColor", "white") ?: "white"
        set(v) = sp.edit { putString("subColor", v) }

    /** "black", "semi" or "none". */
    var subtitleBg: String
        get() = sp.getString("subBg", "semi") ?: "semi"
        set(v) = sp.edit { putString("subBg", v) }
}
