package com.weinet.newvideoplayer.data

import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Rename and delete for videos owned by other apps. On Android 11+ the system asks the user
 * first ([deleteRequest]/[writeRequest] return the consent dialog); on older versions the app
 * acts directly with storage permission.
 */
object MediaActions {

    fun deleteRequest(context: Context, uri: Uri): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender
        else null

    fun writeRequest(context: Context, uri: Uri): IntentSender? =
        if (Build.VERSION.SDK_INT >= 30) MediaStore.createWriteRequest(context.contentResolver, listOf(uri)).intentSender
        else null

    fun deleteDirect(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.delete(uri, null, null) > 0
    } catch (e: SecurityException) { false }

    /** Keeps the original extension; [newName] is the name without it. */
    @Suppress("DEPRECATION")
    fun rename(context: Context, video: Video, newName: String): Boolean = try {
        val ext = video.title.substringAfterLast('.', "")
        val full = if (ext.isNotEmpty() && !newName.endsWith(".$ext")) "$newName.$ext" else newName
        if (Build.VERSION.SDK_INT >= 30) {
            val v = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, full) }
            context.contentResolver.update(video.uri, v, null, null) > 0
        } else {
            val oldPath = context.contentResolver.query(video.uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
            val old = File(oldPath)
            val target = File(old.parentFile, full)
            if (target.exists() || !old.renameTo(target)) return false
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DATA, target.absolutePath)
                put(MediaStore.Video.Media.DISPLAY_NAME, full)
            }
            context.contentResolver.update(video.uri, v, null, null)
            MediaScannerConnection.scanFile(context, arrayOf(old.absolutePath, target.absolutePath), null, null)
            true
        }
    } catch (e: Exception) { false }
}
