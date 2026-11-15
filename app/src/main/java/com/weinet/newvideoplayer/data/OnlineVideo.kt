package com.weinet.newvideoplayer.data

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.media3.common.MimeTypes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.zip.CRC32

/**
 * Videos opened from a link: direct files (MP4, MKV, WebM…), HLS (m3u8) and DASH (mpd).
 * No site-specific page parsing (X, YouTube…): only links that point at the video itself.
 */
object OnlineVideo {
    data class Entry(val url: String, val title: String, val time: Long)

    private const val MAX = 30
    private val _history = MutableStateFlow<List<Entry>>(emptyList())
    val history: StateFlow<List<Entry>> = _history
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("online", Context.MODE_PRIVATE)
        prefs = p
        _history.value = runCatching {
            JSONArray(p.getString("history", "[]")).let { a ->
                List(a.length()) { a.getJSONObject(it).let { o -> Entry(o.getString("u"), o.optString("t"), o.optLong("w")) } }
            }
        }.getOrDefault(emptyList())
    }

    private fun save() {
        val a = JSONArray()
        _history.value.forEach { a.put(JSONObject().put("u", it.url).put("t", it.title).put("w", it.time)) }
        prefs?.edit()?.putString("history", a.toString())?.apply()
    }

    fun remember(url: String, title: String) {
        _history.value = (listOf(Entry(url, title, System.currentTimeMillis())) + _history.value.filter { it.url != url }).take(MAX)
        save()
    }

    fun forget(url: String) { _history.value = _history.value.filter { it.url != url }; save() }
    fun clear() { _history.value = emptyList(); save() }

    /** Pulls the first http(s) link out of shared text ("Look at this https://…"). */
    fun extractUrl(text: String?): String? =
        text?.let { Regex("""https?://\S+""").find(it)?.value?.trimEnd(')', ']', '>', '"', '\'', '，', '。') }

    fun isValid(url: String): Boolean {
        val u = runCatching { Uri.parse(url.trim()) }.getOrNull() ?: return false
        return (u.scheme == "http" || u.scheme == "https") && !u.host.isNullOrBlank()
    }

    private val pageHosts = listOf(
        "x.com", "twitter.com", "t.co", "youtube.com", "youtu.be", "instagram.com",
        "tiktok.com", "facebook.com", "fb.watch", "threads.net", "bilibili.com", "vimeo.com", "dailymotion.com",
    )

    /** Post or page links on social and video sites: those need site-specific parsing, which we don't do. */
    fun isUnsupportedSite(url: String): Boolean {
        val host = Uri.parse(url.trim()).host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return false
        return pageHosts.any { host == it || host.endsWith(".$it") }
    }

    private fun ext(url: String): String =
        Uri.parse(url).lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()

    fun mimeOf(url: String): String {
        val lower = url.lowercase()
        return when {
            ext(url) == "m3u8" || ".m3u8?" in lower -> MimeTypes.APPLICATION_M3U8
            ext(url) == "mpd" || ".mpd?" in lower -> MimeTypes.APPLICATION_MPD
            else -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(url)) ?: "video/*"
        }
    }

    fun isStream(url: String) = mimeOf(url).let { it == MimeTypes.APPLICATION_M3U8 || it == MimeTypes.APPLICATION_MPD }

    fun titleOf(url: String): String {
        val u = Uri.parse(url)
        return u.lastPathSegment?.takeIf { it.isNotBlank() } ?: u.host ?: url
    }

    /** Stable negative id so resume positions work and the item never mixes with MediaStore ids. */
    fun idOf(url: String): Long {
        val c = CRC32(); c.update(url.toByteArray())
        return -(c.value + 1)
    }

    fun toVideo(url: String, title: String = titleOf(url)): Video = Video(
        id = idOf(url), uri = Uri.parse(url), title = title, folder = Uri.parse(url).host.orEmpty(),
        durationMs = 0, sizeBytes = 0, dateAdded = System.currentTimeMillis() / 1000, mimeType = mimeOf(url),
    )

    /** Hands a direct file to the system downloader; it lands in Movies/NewVideoPlayer with a notification. */
    fun download(context: Context, url: String): String {
        var name = titleOf(url).replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (!name.contains('.')) name += ".mp4"
        val req = DownloadManager.Request(Uri.parse(url))
            .setTitle(name)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_MOVIES, "NewVideoPlayer/$name")
            .setAllowedOverMetered(true)
        @Suppress("DEPRECATION") req.allowScanningByMediaScanner()
        (context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        return name
    }
}
