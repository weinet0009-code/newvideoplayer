package com.weinet.newvideoplayer.tools

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.vault.VaultCrypto
import com.weinet.newvideoplayer.vault.VaultStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileDescriptor
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/**
 * Copies a video's audio track into an audio file without re-encoding (fast, no quality loss).
 * AAC becomes .m4a, Opus/Vorbis becomes .webm. For any codec the phone can decode, use MP3 ([Mp3Encoder]).
 */
object AudioExtractor {
    class Unsupported(val mime: String) : Exception("unsupported audio: $mime")
    class NoAudio : Exception("no audio track")

    private data class Out(val ext: String, val muxer: Int, val mime: String)

    private fun outFor(mime: String): Out? = when (mime) {
        MediaFormat.MIMETYPE_AUDIO_AAC -> Out("m4a", MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, "audio/mp4")
        MediaFormat.MIMETYPE_AUDIO_OPUS, MediaFormat.MIMETYPE_AUDIO_VORBIS ->
            Out("webm", MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM, "audio/webm")
        else -> null
    }

    /** Decrypting source so private-folder videos can be read by MediaExtractor. */
    private class VaultSource(context: Context, id: String) : MediaDataSource() {
        private val reader = VaultCrypto.Reader(context, VaultStore.file(context, id))
        @Synchronized
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int =
            if (size == 0) 0 else reader.read(position, buffer, offset, size)
        override fun getSize(): Long = reader.length
        override fun close() = reader.close()
    }

    internal fun openExtractor(context: Context, video: Video): MediaExtractor = MediaExtractor().apply {
        val id = video.vaultId
        if (id != null) setDataSource(VaultSource(context, id)) else setDataSource(context, video.uri, null)
    }

    internal fun audioTrack(ex: MediaExtractor): Pair<Int, MediaFormat> {
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i to f
        }
        throw NoAudio()
    }

    private fun baseName(video: Video) = video.title.substringBeforeLast('.').ifBlank { "audio" }

    /** The extension the output will have, or throws [Unsupported]/[NoAudio]. */
    private fun probe(context: Context, video: Video): Out {
        val ex = openExtractor(context, video)
        try {
            val (_, f) = audioTrack(ex)
            val mime = f.getString(MediaFormat.KEY_MIME)!!
            return outFor(mime) ?: throw Unsupported(mime)
        } finally { ex.release() }
    }

    private fun extract(context: Context, video: Video, fd: FileDescriptor, onProgress: (Float) -> Unit) {
        val ex = openExtractor(context, video)
        var muxer: MediaMuxer? = null
        try {
            val (track, fmt) = audioTrack(ex)
            val out = outFor(fmt.getString(MediaFormat.KEY_MIME)!!)!!
            ex.selectTrack(track)
            val m = MediaMuxer(fd, out.muxer)
            muxer = m
            val t = m.addTrack(fmt)
            m.start()
            val cap = if (fmt.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) fmt.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 1 shl 20
            val buf = ByteBuffer.allocate(cap.coerceAtLeast(1 shl 16))
            val info = MediaCodec.BufferInfo()
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else video.durationMs * 1000
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, n, ex.sampleTime, flags)
                m.writeSampleData(t, buf, info)
                if (durUs > 0) onProgress(ex.sampleTime.toFloat() / durUs)
                ex.advance()
            }
            m.stop()
        } finally {
            muxer?.release()
            ex.release()
        }
    }

    /**
     * Saves the audio to Music/NewVideoPlayer; returns the file name.
     * [mp3] re-encodes to MP3 with LAME; otherwise the original track is copied (m4a/webm).
     */
    @Suppress("DEPRECATION")
    suspend fun toMusic(context: Context, video: Video, mp3: Boolean = false, onProgress: (Float) -> Unit): String = withContext(Dispatchers.IO) {
        val out = if (mp3) Out("mp3", -1, "audio/mpeg") else probe(context, video)
        val name = "${baseName(video)}.${out.ext}"
        fun write(fd: FileDescriptor) {
            if (mp3) java.io.FileOutputStream(fd).buffered(1 shl 16).let { s -> Mp3Encoder.encode(context, video, s, onProgress); s.flush() }
            else extract(context, video, fd, onProgress)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, out.mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/NewVideoPlayer")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                ?: error("cannot create file")
            try {
                resolver.openFileDescriptor(uri, if (mp3) "w" else "rw")!!.use { write(it.fileDescriptor) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "NewVideoPlayer").apply { mkdirs() }
            val file = File(dir, name)
            RandomAccessFile(file, "rw").use { it.setLength(0); write(it.fd) }
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(out.mime), null)
        }
        "Music/NewVideoPlayer/$name"
    }

    /** Writes the audio to a temporary file in the app cache (used for AI subtitles). */
    suspend fun toCache(context: Context, video: Video, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val out = probe(context, video)
        val file = File(File(context.cacheDir, "audio").apply { mkdirs() }, "extract.${out.ext}")
        file.delete()
        RandomAccessFile(file, "rw").use { extract(context, video, it.fd, onProgress) }
        file
    }
}
