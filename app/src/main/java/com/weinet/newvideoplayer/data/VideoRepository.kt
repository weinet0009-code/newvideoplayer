package com.weinet.newvideoplayer.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Scans every video on the device and SD card through MediaStore. */
class VideoRepository(private val context: Context) {

    @Suppress("DEPRECATION")
    suspend fun loadVideos(): List<Video> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.RELATIVE_PATH else MediaStore.Video.Media.DATA
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            pathCol,
        )
        val result = mutableListOf<Video>()
        context.contentResolver.query(
            collection, projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val pCol = c.getColumnIndexOrThrow(pathCol)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                result += Video(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(nameCol) ?: "",
                    folder = c.getString(folderCol) ?: "Other",
                    durationMs = c.getLong(durCol),
                    sizeBytes = c.getLong(sizeCol),
                    dateAdded = c.getLong(dateCol),
                    mimeType = c.getString(mimeCol) ?: "video/*",
                    width = c.getInt(wCol),
                    height = c.getInt(hCol),
                    path = c.getString(pCol) ?: "",
                )
            }
        }
        result
    }

    /** Every song on the device (MediaStore IS_MUSIC), newest first. */
    @Suppress("DEPRECATION")
    suspend fun loadAudio(): List<Video> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val folderCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.BUCKET_DISPLAY_NAME else MediaStore.Audio.Media.DATA
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            folderCol,
            pathCol,
        ).distinct().toTypedArray()
        val result = mutableListOf<Video>()
        runCatching {
            context.contentResolver.query(
                collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )
        }.getOrNull()?.use { c ->
            fun col(n: String) = c.getColumnIndexOrThrow(n)
            val idC = col(MediaStore.Audio.Media._ID); val titleC = col(MediaStore.Audio.Media.TITLE)
            val nameC = col(MediaStore.Audio.Media.DISPLAY_NAME); val artistC = col(MediaStore.Audio.Media.ARTIST)
            val albumC = col(MediaStore.Audio.Media.ALBUM); val albumIdC = col(MediaStore.Audio.Media.ALBUM_ID)
            val durC = col(MediaStore.Audio.Media.DURATION); val sizeC = col(MediaStore.Audio.Media.SIZE)
            val dateC = col(MediaStore.Audio.Media.DATE_ADDED); val mimeC = col(MediaStore.Audio.Media.MIME_TYPE)
            val folderC = col(folderCol); val pathC = col(pathCol)
            while (c.moveToNext()) {
                val id = c.getLong(idC)
                val rawFolder = c.getString(folderC) ?: ""
                val folder = if (Build.VERSION.SDK_INT >= 29) rawFolder else java.io.File(rawFolder).parentFile?.name ?: ""
                val artist = c.getString(artistC)?.takeIf { it != "<unknown>" } ?: ""
                result += Video(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    title = c.getString(titleC)?.takeIf { it.isNotBlank() } ?: c.getString(nameC) ?: "",
                    folder = folder.ifBlank { "Other" },
                    durationMs = c.getLong(durC),
                    sizeBytes = c.getLong(sizeC),
                    dateAdded = c.getLong(dateC),
                    mimeType = c.getString(mimeC) ?: "audio/*",
                    path = c.getString(pathC) ?: "",
                    isAudio = true,
                    artist = artist,
                    album = c.getString(albumC) ?: "",
                    albumId = c.getLong(albumIdC),
                )
            }
        }
        result
    }
}
