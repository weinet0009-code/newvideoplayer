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
    /** True for songs from the music library (same model so playlists can mix both). */
    val isAudio: Boolean = false,
    val artist: String = "",
    val album: String = "",
    val albumId: Long = 0,
) {
    /** Album cover for songs (Coil loads it through MediaStore). */
    val artUri: Uri?
        get() = if (!isAudio || albumId == 0L) null
        else if (android.os.Build.VERSION.SDK_INT >= 29)
            android.content.ContentUris.withAppendedId(android.provider.MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI, albumId)
        else android.content.ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
}

/** Private videos get a non-numeric media id so they never enter "recent" or resume history. */
fun Video.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(if (vaultId != null) "v$vaultId" else id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder().setTitle(title).setDisplayTitle(title).apply {
            if (isAudio) {
                setArtist(artist).setAlbumTitle(album).setArtworkUri(artUri)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            }
        }.build()
    )
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
