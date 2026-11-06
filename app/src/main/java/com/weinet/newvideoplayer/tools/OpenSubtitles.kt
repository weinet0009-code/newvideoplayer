package com.weinet.newvideoplayer.tools

import android.content.Context
import com.weinet.newvideoplayer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * OpenSubtitles.com REST API v1. The app's own consumer key comes from the build
 * (OPENSUBTITLES_API_KEY). Without signing in, each IP gets 5 downloads a day;
 * signing in with an OpenSubtitles account uses that account's quota instead.
 */
object OpenSubtitles {
    private const val BASE = "https://api.opensubtitles.com/api/v1"
    private val userAgent = "NewVideoPlayer v${BuildConfig.VERSION_NAME}"
    val available: Boolean get() = BuildConfig.OPENSUBTITLES_API_KEY.isNotEmpty()

    data class Result(val fileId: Long, val name: String, val language: String, val downloads: Int)

    fun queryFor(title: String) = title.substringBeforeLast('.')
        .replace(Regex("[._]+"), " ")
        .replace(Regex("(?i)\\b(1080p|720p|2160p|4k|x264|x265|h264|h265|hevc|web-?dl|bluray|webrip|hdr)\\b.*"), "")
        .trim()

    private fun connect(path: String, method: String = "GET", token: String? = null): HttpURLConnection =
        (URL(if (path.startsWith("http")) path else "$BASE$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Api-Key", BuildConfig.OPENSUBTITLES_API_KEY)
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    private fun HttpURLConnection.body(): String {
        val code = responseCode
        val stream = if (code in 200..299) inputStream else errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
            throw IOException(msg?.takeIf { it.isNotBlank() } ?: "HTTP $code")
        }
        return text
    }

    private fun HttpURLConnection.postJson(json: JSONObject): String {
        doOutput = true
        setRequestProperty("Content-Type", "application/json")
        outputStream.use { it.write(json.toString().toByteArray()) }
        return body()
    }

    suspend fun search(query: String, languages: String): List<Result> = withContext(Dispatchers.IO) {
        // The API wants parameters sorted and lowercase.
        val q = URLEncoder.encode(query.lowercase(), "UTF-8")
        val json = JSONObject(connect("/subtitles?languages=$languages&query=$q").body())
        val data = json.optJSONArray("data") ?: return@withContext emptyList()
        buildList {
            for (i in 0 until data.length()) {
                val a = data.getJSONObject(i).getJSONObject("attributes")
                val files = a.optJSONArray("files") ?: continue
                if (files.length() == 0) continue
                val f = files.getJSONObject(0)
                add(Result(f.getLong("file_id"), f.optString("file_name", a.optString("release")),
                    a.optString("language"), a.optInt("download_count")))
            }
        }
    }

    suspend fun login(username: String, password: String): String = withContext(Dispatchers.IO) {
        val res = connect("/login", "POST").postJson(JSONObject().put("username", username).put("password", password))
        JSONObject(res).getString("token")
    }

    /** Downloads the subtitle to the app cache and returns the file. */
    suspend fun download(context: Context, r: Result, token: String?): File = withContext(Dispatchers.IO) {
        val res = JSONObject(connect("/download", "POST", token).postJson(JSONObject().put("file_id", r.fileId)))
        val link = res.getString("link")
        val name = res.optString("file_name").ifBlank { "${r.fileId}.srt" }
        val dir = File(context.cacheDir, "subs").apply { mkdirs() }
        val out = File(dir, "${r.fileId}.${name.substringAfterLast('.', "srt")}")
        (URL(link).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", userAgent)
            connectTimeout = 15_000; readTimeout = 30_000
        }.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }
}
