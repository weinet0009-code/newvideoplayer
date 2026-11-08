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

    private val timeLine = Regex("""(\d+):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d+):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val shortTimeLine = Regex("""(\d{2}):(\d{2})[.](\d{3})\s*-->\s*(\d{2}):(\d{2})[.](\d{3})""")

    /** Parses SRT or WebVTT text into cues (VTT styling tags are kept as text). */
    fun parse(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF").split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val m = timeLine.find(line)
            val sm = if (m == null) shortTimeLine.find(line) else null
            if (m == null && sm == null) { i++; continue }
            val (start, end) = if (m != null) {
                val g = m.groupValues
                ms(g[1], g[2], g[3], g[4]) to ms(g[5], g[6], g[7], g[8])
            } else {
                val g = sm!!.groupValues
                ms("0", g[1], g[2], g[3]) to ms("0", g[4], g[5], g[6])
            }
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i]); i++
            }
            if (body.isNotBlank()) out += Segment(start, end, body.toString())
        }
        return out
    }

    private fun ms(h: String, m: String, s: String, f: String): Long =
        h.toLong() * 3_600_000 + m.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').toLong()
}
