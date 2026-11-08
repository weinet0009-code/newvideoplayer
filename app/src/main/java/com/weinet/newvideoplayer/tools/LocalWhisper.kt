package com.weinet.newvideoplayer.tools

import android.content.Context
import com.weinet.newvideoplayer.data.Video
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        Turbo("ggml-large-v3-turbo-q5_0.bin", "最準（large-v3-turbo）", 547, "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2"),
    }

    /**
     * Models offered on this phone. Turbo needs about 1 GB while running, so it's shown only on
     * phones with 8 GB of RAM or more (Android reports a bit less than the label, about 7.2-7.7 GB
     * on an 8 GB phone, hence the 7 GB line). A Turbo already downloaded stays listed so it can be deleted.
     */
    fun models(context: Context): List<Model> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val bigEnough = info.totalMem >= 7_000_000_000L
        return Model.entries.filter { it != Model.Turbo || bigEnough || isDownloaded(context, it) }
    }

    private const val CHUNK_SECONDS = 300

    /** Silero voice-activity model (~0.9 MB): lets Whisper skip silence and music-only parts. */
    private const val VAD_FILE = "ggml-silero-v6.2.0.bin"
    private const val VAD_SHA256 = "2aa269b785eeb53a82983a20501ddf7c1d9c48e33ab63a41391ac6c9f7fb6987"
    private const val VAD_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$VAD_FILE"

    @JvmStatic private external fun nativeInit(path: String): Long
    @JvmStatic private external fun nativeFree(ptr: Long)
    @JvmStatic private external fun nativeAbort(flag: Boolean)
    @JvmStatic private external fun nativeTranscribe(ptr: Long, pcm: FloatArray, lang: String?, prompt: String?, threads: Int, vadModel: String?): ByteArray?
    @JvmStatic private external fun nativeLoadBackends(dir: String)
    @JvmStatic private external fun nativeSystemInfo(): String
    @JvmStatic private external fun nativeProgress(): Int
    @JvmStatic private external fun nativeLastLang(ptr: Long): String?

    private fun ensureBackends(context: Context) = nativeLoadBackends(context.applicationInfo.nativeLibraryDir)

    /** True when the phone's CPU got the fast build (ARMv8.2+ dotprod or fp16 instructions). */
    fun isAccelerated(context: Context): Boolean {
        ensureBackends(context)
        val info = nativeSystemInfo()
        return "DOTPROD = 1" in info || "FP16_VA = 1" in info
    }

    fun vadFile(context: Context) = File(File(context.filesDir, "whisper").apply { mkdirs() }, VAD_FILE)

    fun modelFile(context: Context, m: Model) = File(File(context.filesDir, "whisper").apply { mkdirs() }, m.file)
    fun isDownloaded(context: Context, m: Model) = modelFile(context, m).exists()
    fun delete(context: Context, m: Model) = modelFile(context, m).delete()

    /** Stops a running transcription as soon as whisper.cpp checks in. */
    fun cancel() = nativeAbort(true)

    suspend fun download(context: Context, m: Model, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = fetch("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/${m.file}", modelFile(context, m), m.sha256, onProgress)
        ensureVad(context)
        file
    }

    /** Downloads the small VAD model if it is missing. Returns null when it can't be fetched. */
    suspend fun ensureVad(context: Context): File? = withContext(Dispatchers.IO) {
        val f = vadFile(context)
        if (f.exists()) return@withContext f
        try { fetch(VAD_URL, f, VAD_SHA256) {} } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
    }

    private suspend fun fetch(url: String, target: File, sha256: String, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val part = File(target.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
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
        if (hex != sha256) { part.delete(); throw IOException("模型檔驗證失敗") }
        if (!part.renameTo(target)) throw IOException("無法儲存模型")
        target
    }

    /**
     * Transcribes [video] and writes an SRT file to the cache. [language] is a Whisper code
     * ("zh", "en", "ja") or null for auto-detect.
     */
    suspend fun transcribe(context: Context, video: Video, m: Model, language: String?, skipSilence: Boolean, onProgress: (Float) -> Unit): AiResult =
        withContext(Dispatchers.Default) {
            val job = coroutineContext[Job]
            ensureBackends(context)
            val vad = if (skipSilence) ensureVad(context)?.path else null
            nativeAbort(false)
            val ctx = nativeInit(modelFile(context, m).path)
            if (ctx == 0L) throw IOException("模型載入失敗，請重新下載")
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
            val segments = mutableListOf<Segment>()
            var detected: String? = language
            var done = 0f
            var chunkEnd = 0f
            // whisper.cpp reports percent within the current chunk; turn it into overall progress.
            val ticker = launch {
                while (isActive) {
                    delay(1000)
                    onProgress(done + (chunkEnd - done) * nativeProgress().coerceIn(0, 100) / 100f)
                }
            }
            try {
                PcmDecoder.decode(context, video, CHUNK_SECONDS) { pcm, offsetMs, progress ->
                    job?.ensureActive()
                    chunkEnd = progress
                    // The first chunk's detected language is kept for the rest, so it can't flip midway.
                    val lang = detected
                    val bytes = nativeTranscribe(ctx, pcm, lang, LangCodes.prompt(lang), threads, vad)
                    job?.ensureActive()
                    if (bytes == null) throw IOException("辨識失敗")
                    segments += Srt.parseNative(bytes, offsetMs)
                    if (detected == null) detected = LangCodes.normalize(nativeLastLang(ctx)).takeIf { it != "und" }
                    done = progress
                    onProgress(progress)
                    true
                }
            } finally {
                ticker.cancel()
                nativeFree(ctx)
            }
            val out = File(File(context.cacheDir, "subs").apply { mkdirs() }, "ai-${System.currentTimeMillis()}.srt")
            out.writeText(Srt.format(LangCodes.toTraditional(detected, segments)))
            AiResult(out, LangCodes.normalize(detected))
        }
}
