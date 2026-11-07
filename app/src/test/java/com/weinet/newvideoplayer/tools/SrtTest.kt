package com.weinet.newvideoplayer.tools

import org.junit.Assert.assertEquals
import org.junit.Test

class SrtTest {
    @Test fun formatsTimes() {
        assertEquals("01:02:03,450", Srt.time(3_723_450))
        assertEquals("00:00:00,000", Srt.time(0))
    }

    @Test fun parsesNativeRecordsWithOffset() {
        val raw = "0\u001f250\u001f 你好\u001e250\u001f400\u001fworld\u001e".toByteArray()
        val segs = Srt.parseNative(raw, 300_000)
        assertEquals(2, segs.size)
        assertEquals(Segment(300_000, 302_500, " 你好"), segs[0])
        val srt = Srt.format(segs)
        assertEquals("1\n00:05:00,000 --> 00:05:02,500\n你好\n\n2\n00:05:02,500 --> 00:05:04,000\nworld\n\n", srt)
    }
}
