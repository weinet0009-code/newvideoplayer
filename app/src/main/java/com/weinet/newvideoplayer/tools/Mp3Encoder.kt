package com.weinet.newvideoplayer.tools

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import com.weinet.newvideoplayer.data.Video
import java.io.OutputStream
import java.nio.ByteOrder

/**
 * Real MP3 output: decodes the video's audio with MediaCodec and re-encodes it with LAME
 * (CBR, stereo or mono). Slower than copying the track, but plays everywhere.
 */
object Mp3Encoder {
    init { System.loadLibrary("nvplame") }

    @JvmStatic private external fun nativeInit(sampleRate: Int, channels: Int, kbps: Int): Long
    @JvmStatic private external fun nativeEncode(handle: Long, pcm: ShortArray, frames: Int, channels: Int, out: ByteArray): Int
    @JvmStatic private external fun nativeFlush(handle: Long, out: ByteArray): Int
    @JvmStatic private external fun nativeClose(handle: Long)

    const val KBPS = 192

    fun encode(context: Context, video: Video, out: OutputStream, onProgress: (Float) -> Unit) {
        val ex = AudioExtractor.openExtractor(context, video)
        var codec: MediaCodec? = null
        var lame = 0L
        try {
            val (track, fmt) = AudioExtractor.audioTrack(ex)
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw AudioExtractor.Unsupported(mime) }
            codec = c
            try { c.configure(fmt, null, null, 0) } catch (e: Exception) { throw AudioExtractor.Unsupported(mime) }
            c.start()
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else video.durationMs * 1000

            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var inChannels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            var pcm = ShortArray(0)
            var mp3 = ByteArray(0)

            fun ensureLame(): Int {
                val outCh = if (inChannels == 1) 1 else 2
                if (lame == 0L) {
                    lame = nativeInit(sampleRate, outCh, KBPS)
                    if (lame == 0L) error("LAME init failed ($sampleRate Hz, $outCh ch)")
                }
                return outCh
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val idx = c.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val buf = c.getInputBuffer(idx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(idx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = c.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = c.outputFormat
                    if (lame == 0L) {
                        sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        inChannels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val bb = ob.slice().order(ByteOrder.nativeOrder())
                        val outCh = ensureLame()
                        val total = if (floatPcm) bb.remaining() / 4 else bb.remaining() / 2
                        val frames = total / inChannels
                        if (pcm.size < frames * outCh) pcm = ShortArray(frames * outCh)
                        val fb = if (floatPcm) bb.asFloatBuffer() else null
                        val sb = if (floatPcm) null else bb.asShortBuffer()
                        fun sample(i: Int): Int =
                            if (fb != null) (fb.get(i).coerceIn(-1f, 1f) * 32767f).toInt() else sb!!.get(i).toInt()
                        for (f in 0 until frames) {
                            val base = f * inChannels
                            if (outCh == 1) pcm[f] = sample(base).toShort()
                            else if (inChannels == 2) {
                                pcm[2 * f] = sample(base).toShort(); pcm[2 * f + 1] = sample(base + 1).toShort()
                            } else {
                                // Surround: front left/right plus half of the centre channel.
                                val centre = if (inChannels > 2) sample(base + 2) / 2 else 0
                                pcm[2 * f] = (sample(base) + centre).coerceIn(-32768, 32767).toShort()
                                pcm[2 * f + 1] = (sample(base + 1) + centre).coerceIn(-32768, 32767).toShort()
                            }
                        }
                        val need = (1.25 * frames + 7200).toInt()
                        if (mp3.size < need) mp3 = ByteArray(need)
                        val n = nativeEncode(lame, pcm, frames, outCh, mp3)
                        if (n < 0) error("LAME encode error $n")
                        out.write(mp3, 0, n)
                    }
                    if (durUs > 0) onProgress((info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f))
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (lame != 0L) {
                if (mp3.size < 7200) mp3 = ByteArray(7200)
                val n = nativeFlush(lame, mp3)
                if (n > 0) out.write(mp3, 0, n)
            }
            out.flush()
        } finally {
            if (lame != 0L) nativeClose(lame)
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            ex.release()
        }
    }
}
