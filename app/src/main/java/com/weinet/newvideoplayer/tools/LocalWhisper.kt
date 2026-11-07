package com.weinet.newvideoplayer.tools

import android.content.Context
import com.weinet.newvideoplayer.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Offline AI subtitles: whisper.cpp running on the phone. Free, no account, no network
 * after the one-time model download.
 */
object LocalWhisper {
    init { System.loadLibrary("nvpwhisper") }

    enum class Model(val file: String, val label: String, val sizeMb: Int, val sha256: String) {
        Tiny("ggml-tiny-q5_1.bin", "快速（tiny）", 30, "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"),
        Base("ggml-base-q5_1.bin", "推薦（base）", 56, "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"),
        Small("ggml-small-q5_1.bin", "較準（small）", 181, "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb"),
    }

    private const val CHUNK_SECONDS = 300

    @JvmStatic private external fun nativeInit(path: String): Long
    @JvmStatic private external fun nativeFree(ptr: Long)
    @JvmStatic private external fun nativeAbort(flag: Boolean)
    @JvmStatic private external fun nativeTranscribe(ptr: Long, pcm: FloatArray, lang: String?, prompt: String?, threads: Int): ByteArray?

    fun modelFile(context: Context, m: Model) = File(File(context.filesDir, "whisper").apply { mkdirs() }, m.file)
    fun isDownloaded(context: Context, m: Model) = modelFile(context, m).exists()
    fun delete(context: Context, m: Model) = modelFile(context, m).delete()

    /** Stops a running transcription as soon as whisper.cpp checks in. */
    fun cancel() = nativeAbort(true)

    suspend fun download(context: Context, m: Model, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val target = modelFile(context, m)
        val part = File(target.path + ".part")
        val conn = URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${m.file}").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "NewVideoPlayer")
        if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
        val total = conn.contentLengthLong
        val digest = MessageDigest.getInstance("SHA-256")
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = 0L
                while (true) {
                    job?.ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    digest.update(buf, 0, n)
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (hex != m.sha256) { part.delete(); throw IOException("模型檔驗證失敗") }
        if (!part.renameTo(target)) throw IOException("無法儲存模型")
        target
    }

    /**
     * Transcribes [video] and writes an SRT file to the cache. [language] is a Whisper code
     * ("zh", "en", "ja") or null for auto-detect.
     */
    suspend fun transcribe(context: Context, video: Video, m: Model, language: String?, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.Default) {
            val job = coroutineContext[Job]
            nativeAbort(false)
            val ctx = nativeInit(modelFile(context, m).path)
            if (ctx == 0L) throw IOException("模型載入失敗，請重新下載")
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            // Nudges Whisper towards Traditional Chinese characters instead of Simplified.
            val prompt = if (language == "zh") "以下是繁體中文的字幕。" else null
            val segments = mutableListOf<Segment>()
            try {
                PcmDecoder.decode(context, video, CHUNK_SECONDS) { pcm, offsetMs, progress ->
                    job?.ensureActive()
                    val bytes = nativeTranscribe(ctx, pcm, language, prompt, threads)
                    job?.ensureActive()
                    if (bytes == null) throw IOException("辨識失敗")
                    segments += Srt.parseNative(bytes, offsetMs)
                    onProgress(progress)
                    true
                }
            } finally {
                nativeFree(ctx)
            }
            val out = File(File(context.cacheDir, "subs").apply { mkdirs() }, "ai-${System.currentTimeMillis()}.srt")
            out.writeText(Srt.format(segments))
            out
        }
}
