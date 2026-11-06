package com.weinet.newvideoplayer.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

data class Video(
    val id: Long,
    val uri: Uri,
    val title: String,
    val folder: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAdded: Long,
)

fun Video.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).setDisplayTitle(title).build())
    .build()

fun formatDuration(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
