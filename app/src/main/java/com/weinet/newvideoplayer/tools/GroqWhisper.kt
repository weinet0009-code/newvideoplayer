package com.weinet.newvideoplayer.tools

import android.content.Context
import com.weinet.newvideoplayer.data.Video
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Cloud subtitles through Groq's hosted Whisper (whisper-large-v3-turbo). Each user pastes their
 * own free API key (console.groq.com/keys); the free plan allows 8 hours of audio a day and
 * 2 hours an hour. The audio is uploaded as 16 kHz mono WAV in 10-minute pieces (about 19 MB,
 * under the 25 MB upload limit).
 */
object GroqWhisper {
    const val KEY_NAME = "groq_api_key"
    const val KEYS_URL = "https://console.groq.com/keys"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/transcriptions"
    private const val MODEL = "whisper-large-v3-turbo"
    private const val CHUNK_SECONDS = 600
    private const val SAMPLE_RATE = 16_000

    class InvalidKey : IOException("Groq 金鑰無效")

    /**
     * Transcribes [video] and writes an SRT file to the cache. [onStatus] gets a line to show
     * while waiting out the free plan's hourly limit.
     */
    suspend fun transcribe(
        context: Context, video: Video, apiKey: String, language: String?,
        onProgress: (Float) -> Unit, onStatus: (String?) -> Unit,
    ): AiResult = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val segments = mutableListOf<Segment>()
        var detected: String? = language
        PcmDecoder.decode(context, video, CHUNK_SECONDS) { pcm, offsetMs, progress ->
            job?.ensureActive()
            val wav = toWav(pcm)
            var attempt = 0
            while (true) {
                job?.ensureActive()
                // The first chunk's detected language is kept for the rest, so it can't flip midway.
                val r = post(apiKey, wav, detected, LangCodes.prompt(detected))
                when {
                    r.code == 200 -> {
                        segments += parse(r.body, offsetMs)
                        if (detected == null) detected = LangCodes.normalize(runCatching { JSONObject(r.body).optString("language") }.getOrNull())
                            .takeIf { it != "und" }
                        break
                    }
                    r.code == 401 -> throw InvalidKey()
                    (r.code == 429 || r.code >= 500) && attempt < 30 -> {
                        // Free-plan limit (or a hiccup): wait as long as Groq asks, then retry.
                        val waitS = (r.retryAfter ?: 20L).coerceIn(5L, 3600L)
                        onStatus(if (r.code == 429) "已達 Groq 免費額度上限，約 ${(waitS + 59) / 60} 分鐘後自動繼續…" else "伺服器忙碌，稍後重試…")
                        var left = waitS * 1000
                        while (left > 0) { job?.ensureActive(); Thread.sleep(500); left -= 500 }
                        onStatus(null)
                        attempt++
                    }
                    else -> throw IOException("Groq 錯誤 ${r.code}：${errorMessage(r.body)}")
                }
            }
            onProgress(progress)
            true
        }
        val out = File(File(context.cacheDir, "subs").apply { mkdirs() }, "ai-${System.currentTimeMillis()}.srt")
        out.writeText(Srt.format(LangCodes.toTraditional(detected, segments)))
        AiResult(out, LangCodes.normalize(detected))
    }

    private class Response(val code: Int, val body: String, val retryAfter: Long?)

    private fun post(key: String, wav: ByteArray, language: String?, prompt: String?): Response {
        val boundary = "nvp" + System.nanoTime()
        val body = ByteArrayOutputStream(wav.size + 2048)
        DataOutputStream(body).use { out ->
            fun field(name: String, value: String) {
                out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n")
                out.write(value.toByteArray(Charsets.UTF_8)); out.writeBytes("\r\n")
            }
            field("model", MODEL)
            field("response_format", "verbose_json")
            field("temperature", "0")
            language?.let { field("language", it) }
            prompt?.let { field("prompt", it) }
            out.writeBytes("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n")
            out.write(wav)
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val bytes = body.toByteArray()
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 180_000
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.setRequestProperty("Authorization", "Bearer $key")
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("User-Agent", "NewVideoPlayer")
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val retry = conn.getHeaderField("retry-after")?.trim()?.toDoubleOrNull()?.toLong()
            return Response(code, text, retry)
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(body: String, offsetMs: Long): List<Segment> {
        val arr = JSONObject(body).optJSONArray("segments") ?: return emptyList()
        val out = mutableListOf<Segment>()
        for (i in 0 until arr.length()) {
            val s = arr.getJSONObject(i)
            // Whisper makes up lines over music or silence; those come back marked as no speech.
            if (s.optDouble("no_speech_prob", 0.0) > 0.8) continue
            val text = s.optString("text").trim()
            if (text.isEmpty()) continue
            out += Segment(offsetMs + (s.optDouble("start") * 1000).toLong(), offsetMs + (s.optDouble("end") * 1000).toLong(), text)
        }
        return out
    }

    private fun errorMessage(body: String): String =
        try { JSONObject(body).getJSONObject("error").getString("message") } catch (_: Exception) { body.take(200) }

    /** 16-bit PCM WAV of [pcm] (mono, 16 kHz, samples in -1..1). */
    private fun toWav(pcm: FloatArray): ByteArray {
        val dataSize = pcm.size * 2
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
        buf.put("data".toByteArray()).putInt(dataSize)
        for (v in pcm) buf.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        return buf.array()
    }
}
