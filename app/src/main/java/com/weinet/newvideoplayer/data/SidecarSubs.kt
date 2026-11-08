package com.weinet.newvideoplayer.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Saves generated subtitles next to their video ("Movie.zh.AI.srt"), where other players find
 * them too. Android doesn't let apps write non-media files into video folders, so the user grants
 * the folder (or a parent of it) once through the system folder picker; the grant is kept.
 */
object SidecarSubs {
    private const val AUTHORITY = "com.android.externalstorage.documents"

    /** "Movies/Trips" for a video on the phone's main storage, or null (SD card, private folder). */
    @Suppress("DEPRECATION")
    fun folderOf(video: Video): String? {
        if (video.vaultId != null || video.path.isBlank()) return null
        val rel = if (Build.VERSION.SDK_INT >= 29) video.path
        else {
            val root = Environment.getExternalStorageDirectory().path.trimEnd('/') + "/"
            if (!video.path.startsWith(root)) return null
            File(video.path.removePrefix(root)).parent ?: ""
        }
        return rel.trim('/').takeIf { it.isNotEmpty() }
    }

    /** "Movie.zh.AI.srt" for "Movie.mp4". */
    fun fileName(video: Video, lang: String, tag: String): String {
        val base = video.title.substringBeforeLast('.').ifBlank { "subtitle" }.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "$base.$lang.$tag.srt"
    }

    private fun docIdOf(folder: String) = "primary:$folder"

    /** The granted tree that covers [folder], if any. */
    private fun treeFor(context: Context, folder: String): Uri? {
        val want = docIdOf(folder).lowercase()
        return context.contentResolver.persistedUriPermissions
            .filter { it.isWritePermission && it.uri.authority == AUTHORITY }
            .map { it.uri }
            .firstOrNull { tree ->
                val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()?.lowercase() ?: return@firstOrNull false
                want == id || want.startsWith(id.trimEnd('/') + "/") || id == "primary:"
            }
    }

    /** True when the video's folder still needs the user's one-time grant. */
    fun needsGrant(context: Context, video: Video): Boolean {
        val folder = folderOf(video) ?: return false
        return treeFor(context, folder) == null
    }

    /** Where the folder picker opens: the video's own folder. */
    fun initialUri(video: Video): Uri? = folderOf(video)?.let { DocumentsContract.buildDocumentUri(AUTHORITY, docIdOf(it)) }

    /** Keeps the folder the user picked. */
    fun rememberGrant(context: Context, tree: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    /**
     * Copies [src] next to [video] as [name], replacing an older file of that name. Returns the
     * path shown to the user ("Movies/Trips/Movie.zh.AI.srt"), or null when the folder isn't
     * granted or can't be written.
     */
    fun save(context: Context, video: Video, src: File, name: String): String? {
        val folder = folderOf(video) ?: return null
        val tree = treeFor(context, folder) ?: return null
        val resolver = context.contentResolver
        return runCatching {
            val parentId = docIdOf(folder)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            var target: Uri? = null
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(1) == name) { target = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)); break }
                }
            }
            val uri = target ?: DocumentsContract.createDocument(
                resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parentId), "application/octet-stream", name,
            ) ?: return null
            resolver.openOutputStream(uri, "wt")!!.use { out -> src.inputStream().use { it.copyTo(out) } }
            "$folder/$name"
        }.getOrNull()
    }
}
