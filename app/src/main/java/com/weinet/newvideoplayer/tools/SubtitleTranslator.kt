package com.weinet.newvideoplayer.tools

import android.content.Context
import android.os.Build
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Translates SRT/VTT subtitles, keeping every cue's timing. */
object SubtitleTranslator {
    /** Target languages offered: code used in file names, label, ML Kit language, name for the LLM. */
    enum class Target(val code: String, val label: String, val mlkit: String, val llmName: String) {
        ZhTw("zh-TW", "繁體中文", TranslateLanguage.CHINESE, "Traditional Chinese as written in Taiwan (台灣繁體中文用語)"),
        En("en", "英文", TranslateLanguage.ENGLISH, "English"),
        Ja("ja", "日文", TranslateLanguage.JAPANESE, "Japanese"),
        Ko("ko", "韓文", TranslateLanguage.KOREAN, "Korean"),
    }

    class QuotaExhausted : IOException("今天的 Groq 免費額度已用完，明天再試或改用離線翻譯")
    class Unsupported(msg: String) : IOException(msg)

    fun read(file: File): List<Segment> {
        val ext = file.extension.lowercase()
        if (ext == "ass" || ext == "ssa" || ext == "ttml" || ext == "xml" || ext == "dfxp") {
            throw Unsupported("目前只能翻譯 SRT 或 VTT 字幕")
        }
        val segs = Srt.parse(file.readText())
        if (segs.isEmpty()) throw Unsupported("字幕檔裡沒有可翻譯的內容")
        return segs
    }

    private fun writeOut(context: Context, segs: List<Segment>): File {
        val out = File(File(context.cacheDir, "subs").apply { mkdirs() }, "tr-${System.currentTimeMillis()}.srt")
        out.writeText(Srt.format(segs))
        return out
    }

    // ---------------------------------------------------------------- Groq (LLM)

    private const val CHAT_URL = "https://api.groq.com/openai/v1/chat/completions"
    private val MODELS = listOf("openai/gpt-oss-120b", "openai/gpt-oss-20b")
    private const val BATCH = 40

    suspend fun translateWithGroq(
        context: Context, src: File, target: Target, apiKey: String,
        onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val segs = read(src)
        val out = segs.toMutableList()
        var model = 0
        var i = 0
        while (i < segs.size) {
            job?.ensureActive()
            val batch = segs.subList(i, minOf(i + BATCH, segs.size))
            val input = batch.mapIndexed { k, s -> "${k + 1}|${s.text.replace("\n", " / ")}" }.joinToString("\n")
            val r = chat(apiKey, MODELS[model], target, input)
            when {
                r.code == 200 -> {
                    val content = JSONObject(r.body).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content")
                    val map = HashMap<Int, String>()
                    for (line in content.lines()) {
                        val m = Regex("""^\s*(\d+)\s*\|\s*(.*)$""").find(line) ?: continue
                        map[m.groupValues[1].toInt()] = m.groupValues[2].trim()
                    }
                    batch.forEachIndexed { k, s ->
                        map[k + 1]?.takeIf { it.isNotEmpty() }?.let { t -> out[i + k] = s.copy(text = t.replace(" / ", "\n")) }
                    }
                    i += batch.size
                    onProgress(i.toFloat() / segs.size)
                }
                r.code == 401 -> throw GroqWhisper.InvalidKey()
                (r.code == 404 || r.code == 400 && "model" in r.body) && model < MODELS.size - 1 -> model++
                r.code == 429 || r.code >= 500 -> {
                    val waitS = (r.retryAfter ?: 20L)
                    if (r.code == 429 && waitS > 900) throw QuotaExhausted()
                    onStatus(if (r.code == 429) "Groq 每分鐘額度已滿，約 ${waitS.coerceAtLeast(1)} 秒後繼續…" else "伺服器忙碌，稍後重試…")
                    delay(waitS.coerceIn(2L, 900L) * 1000)
                    onStatus(null)
                }
                else -> throw IOException("Groq 錯誤 ${r.code}")
            }
        }
        writeOut(context, out)
    }

    private class Resp(val code: Int, val body: String, val retryAfter: Long?)

    private fun chat(key: String, model: String, target: Target, input: String): Resp {
        val system = "You translate movie and TV subtitles into ${target.llmName}. " +
            "Each input line is 'N|text'; ' / ' marks a line break inside one subtitle. " +
            "Reply with exactly one line per input line, in the same order, formatted 'N|translation', keeping ' / '. " +
            "Translate naturally and briefly, as a professional subtitler would. Output nothing else."
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("reasoning_effort", "low")
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", input)))
            .toString().toByteArray()
        val conn = URL(CHAT_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "NewVideoPlayer")
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            val retry = conn.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.toLong()
            return Resp(code, text, retry)
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- ML Kit (offline)

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }

    suspend fun translateWithMlKit(
        context: Context, src: File, target: Target,
        onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
    ): File {
        val segs = withContext(Dispatchers.IO) { read(src) }
        val sample = segs.take(80).joinToString(" ") { it.text }
        val langId = LanguageIdentification.getClient()
        val tag = try { langId.identifyLanguage(sample).awaitTask() } finally { langId.close() }
        val source = TranslateLanguage.fromLanguageTag(tag)
            ?: throw Unsupported(if (tag == "und") "認不出字幕的語言" else "離線翻譯不支援這個語言（$tag）")
        val toTraditional = target == Target.ZhTw
        if (source == target.mlkit) {
            if (!toTraditional) throw Unsupported("字幕已經是${target.label}")
        }
        val translated = if (source == target.mlkit) segs else {
            val translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(target.mlkit).build(),
            )
            try {
                onStatus("下載離線翻譯模型（每種語言約 30 MB，只需一次）…")
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitTask()
                onStatus(null)
                segs.mapIndexed { k, s ->
                    val t = translator.translate(s.text).awaitTask()
                    if (k % 10 == 0) onProgress(k.toFloat() / segs.size)
                    s.copy(text = t)
                }
            } finally {
                translator.close()
            }
        }
        // ML Kit's Chinese is Simplified; turn it into Traditional characters (Android 10+).
        val result = if (toTraditional && Build.VERSION.SDK_INT >= 29) {
            val tr = runCatching { android.icu.text.Transliterator.getInstance("Simplified-Traditional") }.getOrNull()
            if (tr == null) translated else translated.map { it.copy(text = tr.transliterate(it.text)) }
        } else translated
        onProgress(1f)
        return withContext(Dispatchers.IO) { writeOut(context, result) }
    }
}
