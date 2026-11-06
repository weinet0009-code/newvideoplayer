package com.weinet.newvideoplayer.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** AI subtitles: sends the audio to OpenAI's speech-to-text (whisper-1) and gets SRT back. */
object Whisper {
    const val MAX_BYTES = 25L * 1024 * 1024

    suspend fun transcribe(context: Context, apiKey: String, audio: File, language: String?): File = withContext(Dispatchers.IO) {
        val boundary = "----nvp" + UUID.randomUUID().toString().replace("-", "")
        val conn = (URL("https://api.openai.com/v1/audio/transcriptions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 300_000
            setChunkedStreamingMode(1 shl 16)
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.buffered().use { os ->
            fun field(name: String, value: String) {
                os.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            }
            field("model", "whisper-1")
            field("response_format", "srt")
            if (!language.isNullOrBlank()) field("language", language)
            val mime = if (audio.extension == "webm") "audio/webm" else "audio/mp4"
            os.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.${audio.extension}\"\r\nContent-Type: $mime\r\n\r\n".toByteArray())
            audio.inputStream().use { it.copyTo(os) }
            os.write("\r\n--$boundary--\r\n".toByteArray())
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
            throw IOException(msg ?: "HTTP $code")
        }
        val out = File(File(context.cacheDir, "subs").apply { mkdirs() }, "ai-${System.currentTimeMillis()}.srt")
        out.writeText(text)
        out
    }
}
