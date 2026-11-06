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
    val mimeType: String = "video/*",
    val width: Int = 0,
    val height: Int = 0,
    val path: String = "",
    /** Set for videos stored in the private folder. */
    val vaultId: String? = null,
)

/** Private videos get a non-numeric media id so they never enter "recent" or resume history. */
fun Video.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(if (vaultId != null) "v$vaultId" else id.toString())
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

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%d KB".format(bytes / 1024)
}
