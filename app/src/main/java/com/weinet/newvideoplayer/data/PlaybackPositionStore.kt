package com.weinet.newvideoplayer.data

import android.content.Context

/** Remembers where each video was left off. Moves to Room in a later milestone. */
class PlaybackPositionStore(context: Context) {
    private val prefs = context.getSharedPreferences("playback_positions", Context.MODE_PRIVATE)

    fun get(videoId: Long): Long = prefs.getLong(videoId.toString(), 0L)

    fun save(videoId: Long, positionMs: Long, durationMs: Long) {
        val edit = prefs.edit()
        // Finished (or nearly): start from the beginning next time.
        if (durationMs > 0 && positionMs >= durationMs - 3_000) edit.remove(videoId.toString())
        else edit.putLong(videoId.toString(), positionMs)
        edit.apply()
    }
}
