package com.weinet.newvideoplayer.vault

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.weinet.newvideoplayer.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

data class VaultEntry(
    val id: String,
    val name: String,
    val mime: String,
    val durationMs: Long,
    val size: Long,
    val width: Int,
    val height: Int,
    val addedAt: Long,
) {
    fun toVideo() = Video(
        id = UUID.fromString(id).mostSignificantBits,
        uri = Uri.parse("vault://$id"),
        title = name, folder = "私密資料夾", durationMs = durationMs, sizeBytes = size,
        dateAdded = addedAt / 1000, mimeType = mime, width = width, height = height, vaultId = id,
    )
}

/** Encrypted private folder kept in app-only storage. The index itself is encrypted too. */
object VaultStore {
    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries
    private var loaded = false

    private fun dir(context: Context) = File(context.filesDir, "vault").apply { mkdirs() }
    fun file(context: Context, id: String) = File(dir(context), "$id.nvp")
    private fun thumbFile(context: Context, id: String) = File(dir(context), "$id.thumb")
    private fun indexFile(context: Context) = File(dir(context), "index.nvp")

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val f = indexFile(context)
        _entries.value = if (!f.exists()) emptyList() else {
            val arr = JSONArray(String(VaultCrypto.decryptBytes(context, f)))
            List(arr.length()) { i ->
                arr.getJSONObject(i).run {
                    VaultEntry(getString("id"), getString("name"), getString("mime"), getLong("dur"),
                        getLong("size"), getInt("w"), getInt("h"), getLong("added"))
                }
            }
        }
        loaded = true
    }

    private fun saveIndex(context: Context, list: List<VaultEntry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("mime", it.mime).put("dur", it.durationMs)
                .put("size", it.size).put("w", it.width).put("h", it.height).put("added", it.addedAt))
        }
        val tmp = File(dir(context), "index.tmp")
        VaultCrypto.encryptBytes(context, arr.toString().toByteArray(), tmp)
        tmp.renameTo(indexFile(context))
        _entries.value = list
    }

    /** Copies [video] into the vault encrypted. The caller deletes the original afterwards. */
    suspend fun import(context: Context, video: Video, onProgress: (Float) -> Unit): VaultEntry = withContext(Dispatchers.IO) {
        load(context)
        val id = UUID.randomUUID().toString()
        val out = file(context, id)
        try {
            context.contentResolver.openInputStream(video.uri)!!.use { input ->
                VaultCrypto.encryptStream(context, input, out) { done ->
                    if (video.sizeBytes > 0) onProgress(done.toFloat() / video.sizeBytes)
                }
            }
            thumbnailBytes(context, video.uri)?.let { VaultCrypto.encryptBytes(context, it, thumbFile(context, id)) }
        } catch (e: Exception) {
            out.delete(); thumbFile(context, id).delete()
            throw e
        }
        val entry = VaultEntry(id, video.title, video.mimeType, video.durationMs, out.length() - VaultCrypto.HEADER,
            video.width, video.height, System.currentTimeMillis())
        saveIndex(context, _entries.value + entry)
        entry
    }

    private fun thumbnailBytes(context: Context, uri: Uri): ByteArray? = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val bmp = r.getFrameAtTime(1_000_000) ?: return null
            val scaled = Bitmap.createScaledBitmap(bmp, 320, (320f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
            ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray()
        } finally { r.release() }
    } catch (e: Exception) { null }

    suspend fun thumbnail(context: Context, entry: VaultEntry): Bitmap? = withContext(Dispatchers.IO) {
        val f = thumbFile(context, entry.id)
        if (!f.exists()) null else try {
            val b = VaultCrypto.decryptBytes(context, f)
            BitmapFactory.decodeByteArray(b, 0, b.size)
        } catch (e: Exception) { null }
    }

    /** Decrypts back to the public Movies/NewVideoPlayer folder and removes it from the vault. */
    @Suppress("DEPRECATION")
    suspend fun restore(context: Context, entry: VaultEntry, onProgress: (Float) -> Unit): Boolean = withContext(Dispatchers.IO) {
        val buf = ByteArray(1 shl 16)
        try {
            VaultCrypto.Reader(context, file(context, entry.id)).use { reader ->
                fun copyTo(os: java.io.OutputStream) {
                    var pos = 0L
                    while (true) {
                        val n = reader.read(pos, buf, 0, buf.size)
                        if (n <= 0) break
                        os.write(buf, 0, n)
                        pos += n
                        onProgress(pos.toFloat() / reader.length)
                    }
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, entry.name)
                        put(MediaStore.Video.Media.MIME_TYPE, entry.mime)
                        put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/NewVideoPlayer")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                        ?: return@withContext false
                    resolver.openOutputStream(uri)!!.use { copyTo(it) }
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                } else {
                    val outDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "NewVideoPlayer")
                    outDir.mkdirs()
                    var target = File(outDir, entry.name)
                    var i = 1
                    while (target.exists()) target = File(outDir, "${entry.name.substringBeforeLast('.')}($i).${entry.name.substringAfterLast('.', "mp4")}").also { i++ }
                    target.outputStream().use { copyTo(it) }
                    MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(entry.mime), null)
                }
            }
            delete(context, entry)
            true
        } catch (e: Exception) { false }
    }

    fun delete(context: Context, entry: VaultEntry) {
        file(context, entry.id).delete()
        thumbFile(context, entry.id).delete()
        saveIndex(context, _entries.value.filter { it.id != entry.id })
    }

    fun rename(context: Context, entry: VaultEntry, newName: String) {
        val ext = entry.name.substringAfterLast('.', "")
        val full = if (ext.isNotEmpty() && !newName.endsWith(".$ext")) "$newName.$ext" else newName
        saveIndex(context, _entries.value.map { if (it.id == entry.id) it.copy(name = full) else it })
    }
}
