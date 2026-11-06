package com.weinet.newvideoplayer.cast

import android.content.Context
import android.net.ConnectivityManager
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.vault.VaultCrypto
import com.weinet.newvideoplayer.vault.VaultStore
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * A tiny HTTP server on the phone so a Chromecast on the same Wi-Fi can fetch local videos.
 * Supports Range requests (needed for seeking). Only paths with a random per-run token are served.
 */
object LocalMediaServer {
    private var server: ServerSocket? = null
    private var token = ""
    @Volatile private var items: List<Video> = emptyList()
    private lateinit var app: Context
    private val pool = Executors.newCachedThreadPool()

    fun lanIp(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return lp.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Returns one URL per video, or null when the phone has no LAN address. */
    @Synchronized
    fun serve(context: Context, videos: List<Video>): List<String>? {
        app = context.applicationContext
        val ip = lanIp(app) ?: return null
        if (server == null) {
            token = UUID.randomUUID().toString().replace("-", "")
            val s = ServerSocket(0)
            server = s
            pool.execute {
                while (!s.isClosed) {
                    val client = try { s.accept() } catch (e: Exception) { break }
                    pool.execute { handle(client) }
                }
            }
        }
        items = videos
        val port = server!!.localPort
        return videos.indices.map { "http://$ip:$port/$token/$it" }
    }

    @Synchronized
    fun stop() {
        server?.close()
        server = null
        items = emptyList()
    }

    private interface Source : AutoCloseable {
        val length: Long
        fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int
    }

    private fun open(video: Video): Source {
        val id = video.vaultId
        if (id != null) {
            val r = VaultCrypto.Reader(app, VaultStore.file(app, id))
            return object : Source {
                override val length = r.length
                override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) = r.read(pos, buf, off, len)
                override fun close() = r.close()
            }
        }
        val pfd = app.contentResolver.openFileDescriptor(video.uri, "r")!!
        val ch = FileInputStream(pfd.fileDescriptor).channel
        return object : Source {
            override val length = pfd.statSize
            override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) = ch.read(ByteBuffer.wrap(buf, off, len), pos)
            override fun close() { ch.close(); pfd.close() }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun handle(socket: Socket) = socket.use { s ->
        try {
            val input = s.getInputStream()
            val out = s.getOutputStream()
            val request = readLine(input) ?: return@use
            val parts = request.split(" ")
            if (parts.size < 2) return@use
            val method = parts[0]
            val path = parts[1].trim('/').split("/")
            var range: String? = null
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
                if (h.startsWith("range:", ignoreCase = true)) range = h.substringAfter(':').trim()
            }
            val video = if (path.size == 2 && path[0] == token) items.getOrNull(path[1].toIntOrNull() ?: -1) else null
            if (video == null) { status(out, "404 Not Found"); return@use }
            open(video).use { src ->
                val total = src.length
                var start = 0L
                var end = total - 1
                var partial = false
                range?.removePrefix("bytes=")?.split("-")?.let { r ->
                    val a = r.getOrNull(0)?.toLongOrNull()
                    val b = r.getOrNull(1)?.toLongOrNull()
                    when {
                        a != null -> { start = a; if (b != null) end = min(b, total - 1) }
                        b != null -> start = (total - b).coerceAtLeast(0)
                    }
                    partial = true
                }
                if (start > end || start >= total) { status(out, "416 Range Not Satisfiable"); return@use }
                val len = end - start + 1
                val mime = video.mimeType.takeIf { it != "video/*" } ?: "video/mp4"
                val head = buildString {
                    append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: $mime\r\n")
                    append("Accept-Ranges: bytes\r\n")
                    append("Access-Control-Allow-Origin: *\r\n")
                    append("Content-Length: $len\r\n")
                    if (partial) append("Content-Range: bytes $start-$end/$total\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                if (method == "HEAD") { out.flush(); return@use }
                val buf = ByteArray(1 shl 16)
                var pos = start
                while (pos <= end) {
                    val n = src.read(pos, buf, 0, min(buf.size.toLong(), end - pos + 1).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    pos += n
                }
                out.flush()
            }
        } catch (e: Exception) {
            // Client hung up (the receiver often closes and re-requests ranges).
        }
    }

    private fun status(out: OutputStream, line: String) {
        out.write("HTTP/1.1 $line\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

}
