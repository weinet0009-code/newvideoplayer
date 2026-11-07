package com.weinet.newvideoplayer.data

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Keeps the subtitles a user loaded (file, online, AI) for each video, so they come back the
 * next time the video is opened. Files are copied into app storage; the list and the last
 * chosen subtitle are kept per media id.
 */
object SubtitleStore {
    private data class Entry(val id: String, val file: String, val label: String)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("subtitles", Context.MODE_PRIVATE)

    private fun dir(context: Context, mediaId: String) =
        File(File(context.applicationContext.filesDir, "subs"), mediaId.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }

    private fun entries(context: Context, mediaId: String): List<Entry> {
        val a = JSONArray(prefs(context).getString("list_$mediaId", "[]"))
        return List(a.length()) { a.getJSONObject(it).let { o -> Entry(o.getString("id"), o.getString("file"), o.getString("label")) } }
            .filter { File(it.file).exists() }
    }

    private fun write(context: Context, mediaId: String, list: List<Entry>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("file", it.file).put("label", it.label)) }
        prefs(context).edit().putString("list_$mediaId", a.toString()).apply()
    }

    fun mime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /**
     * Copies [source] into app storage for [mediaId], replacing an earlier subtitle with the same
     * label, and marks it as the chosen one. Returns the saved file's uri.
     */
    fun add(context: Context, mediaId: String, source: Uri, name: String, label: String, id: String): Uri {
        val ext = name.substringAfterLast('.', "srt").lowercase().take(5)
        val out = File(dir(context, mediaId), "$id.$ext")
        context.contentResolver.openInputStream(source)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        val old = entries(context, mediaId)
        old.filter { it.label == label }.forEach { File(it.file).delete() }
        write(context, mediaId, old.filter { it.label != label } + Entry(id, out.path, label))
        setSelected(context, mediaId, id)
        return Uri.fromFile(out)
    }

    /** Remembers the chosen subtitle id, or null when subtitles were turned off / an embedded one was picked. */
    fun setSelected(context: Context, mediaId: String, id: String?) =
        prefs(context).edit().putString("sel_$mediaId", id ?: "").apply()

    /** The id of the saved subtitle a track format id belongs to, if any. */
    fun idForFormat(context: Context, mediaId: String, formatId: String?): String? =
        formatId?.let { f -> entries(context, mediaId).firstOrNull { f.contains(it.id) }?.id }

    fun configurations(context: Context, mediaId: String): List<MediaItem.SubtitleConfiguration> {
        val sel = prefs(context).getString("sel_$mediaId", "")
        return entries(context, mediaId).map {
            MediaItem.SubtitleConfiguration.Builder(Uri.fromFile(File(it.file)))
                .setMimeType(mime(it.file))
                .setLanguage("und")
                .setLabel(it.label)
                .setId(it.id)
                .setSelectionFlags(if (it.id == sel) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
        }
    }

    /** Adds the saved subtitles to [item] (no-op when there are none). */
    fun attach(context: Context, item: MediaItem): MediaItem {
        val subs = configurations(context, item.mediaId)
        return if (subs.isEmpty()) item else item.buildUpon().setSubtitleConfigurations(subs).build()
    }

    /**
     * Copies the saved subtitle behind [formatId] (the selected text track), or the only saved one,
     * to Download/NewVideoPlayer named after [videoTitle]. Returns the saved path, or null when
     * there is no saved subtitle to export (e.g. an embedded track is selected).
     */
    @Suppress("DEPRECATION")
    fun export(context: Context, mediaId: String, formatId: String?, videoTitle: String): String? {
        val list = entries(context, mediaId)
        val e = idForFormat(context, mediaId, formatId)?.let { id -> list.first { it.id == id } }
            ?: list.singleOrNull() ?: return null
        val src = File(e.file)
        val base = videoTitle.substringBeforeLast('.').ifBlank { "subtitle" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val suffix = if (e.label == "AI 字幕") ".ai" else ""
        val name = "$base$suffix.${src.extension.ifBlank { "srt" }}"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                // octet-stream keeps the .srt/.ass extension as given.
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "${android.os.Environment.DIRECTORY_DOWNLOADS}/NewVideoPlayer")
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("cannot create file")
            resolver.openOutputStream(uri)!!.use { out -> src.inputStream().use { it.copyTo(out) } }
        } else {
            val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "NewVideoPlayer").apply { mkdirs() }
            src.copyTo(File(dir, name), overwrite = true)
        }
        return "Download/NewVideoPlayer/$name"
    }

    fun forget(context: Context, mediaId: String) {
        dir(context, mediaId).deleteRecursively()
        prefs(context).edit().remove("list_$mediaId").remove("sel_$mediaId").apply()
    }
}
