package com.weinet.newvideoplayer.tools

/** One subtitle line with times in milliseconds. */
data class Segment(val startMs: Long, val endMs: Long, val text: String)

object Srt {
    fun time(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        val f = ms % 1000
        return "%02d:%02d:%02d,%03d".format(h, m, s, f)
    }

    fun format(segments: List<Segment>): String = buildString {
        var n = 1
        for (seg in segments) {
            val text = seg.text.trim()
            if (text.isEmpty()) continue
            append(n++).append('\n')
            append(time(seg.startMs)).append(" --> ").append(time(maxOf(seg.endMs, seg.startMs + 300))).append('\n')
            append(text).append("\n\n")
        }
    }

    /** Parses the native layer's "t0\u001ft1\u001ftext\u001e" records (times in 10 ms units). */
    fun parseNative(bytes: ByteArray, offsetMs: Long): List<Segment> =
        String(bytes, Charsets.UTF_8).split('\u001e').mapNotNull { rec ->
            val p = rec.split('\u001f', limit = 3)
            if (p.size < 3) return@mapNotNull null
            val t0 = p[0].toLongOrNull() ?: return@mapNotNull null
            val t1 = p[1].toLongOrNull() ?: return@mapNotNull null
            Segment(offsetMs + t0 * 10, offsetMs + t1 * 10, p[2])
        }
}
