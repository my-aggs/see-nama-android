package com.seenama.tv.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Opens a YouTube video in the TV's YouTube app (or anything that handles the link). */
fun openYouTube(ctx: Context, url: String): Boolean {
    val uri = Uri.parse(url)
    val attempts = listOf(
        Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube.tv"),
        Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.youtube"),
        Intent(Intent.ACTION_VIEW, uri),
    )
    for (i in attempts) {
        if (runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
    }
    android.widget.Toast.makeText(ctx, "Install YouTube to watch this episode", android.widget.Toast.LENGTH_LONG).show()
    return false
}
