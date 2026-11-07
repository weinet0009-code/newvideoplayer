package com.weinet.newvideoplayer.tools

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import com.weinet.newvideoplayer.data.Video
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Decodes a video's audio to 16 kHz mono float PCM (what Whisper wants) and hands it over in
 * fixed-length chunks so memory stays small even for long videos.
 */
object PcmDecoder {
    const val RATE = 16_000

    /** [onChunk] gets (samples, chunk start in ms, overall progress 0..1). Return false to stop. */
    fun decode(context: Context, video: Video, chunkSeconds: Int, onChunk: (FloatArray, Long, Float) -> Boolean) {
        val ex = AudioExtractor.openExtractor(context, video)
        var codec: MediaCodec? = null
        try {
            val (track, fmt) = AudioExtractor.audioTrack(ex)
            ex.selectTrack(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME)!!
            val c = try { MediaCodec.createDecoderByType(mime) } catch (e: Exception) { throw AudioExtractor.Unsupported(mime) }
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else video.durationMs * 1000

            var sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false

            val chunkLen = chunkSeconds * RATE
            var chunk = FloatArray(chunkLen)
            var filled = 0
            var chunkStartMs = 0L
            var progress = 0f
            var stopped = false

            // Resampler: source mono frames waiting to be consumed.
            var src = FloatArray(1 shl 16)
            var srcLen = 0
            var srcBase = 0L      // global index of src[0]
            var outIndex = 0L     // 16 kHz samples produced so far

            fun emit(v: Float) {
                if (stopped) return
                chunk[filled++] = v
                if (filled == chunkLen) {
                    if (!onChunk(chunk, chunkStartMs, progress)) stopped = true
                    chunk = FloatArray(chunkLen)
                    filled = 0
                    chunkStartMs += chunkSeconds * 1000L
                }
            }

            fun resample() {
                val ratio = sampleRate.toDouble() / RATE
                val width = ceil(ratio).toInt().coerceAtLeast(2)
                while (!stopped) {
                    val pos = outIndex * ratio
                    val i = pos.toLong()
                    if (i + width - srcBase >= srcLen) break
                    val k = (i - srcBase).toInt()
                    val v = if (ratio > 1.0) {
                        // Box average over the source window: a crude low-pass before decimation.
                        var s = 0f
                        val w = ratio.toInt().coerceAtLeast(1)
                        for (j in 0 until w) s += src[k + j]
                        s / w
                    } else {
                        val f = (pos - i).toFloat()
                        src[k] + (src[k + 1] - src[k]) * f
                    }
                    emit(v)
                    outIndex++
                }
                // Drop consumed frames.
                val keepFrom = ((outIndex * ratio).toLong() - srcBase).toInt().coerceIn(0, srcLen)
                if (keepFrom > 0) {
                    System.arraycopy(src, keepFrom, src, 0, srcLen - keepFrom)
                    srcLen -= keepFrom
                    srcBase += keepFrom
                }
            }

            fun push(frames: Int, read: (Int) -> Float) {
                if (srcLen + frames > src.size) src = src.copyOf(maxOf(src.size * 2, srcLen + frames))
                for (f in 0 until frames) {
                    var s = 0f
                    for (ch in 0 until channels) s += read(f * channels + ch)
                    src[srcLen++] = s / channels
                }
                resample()
            }

            val info = MediaCodec.BufferInfo()
            var inputDone = false
            while (!stopped) {
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
                    sampleRate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (oi >= 0) {
                    val ob = c.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val bb = ob.slice().order(ByteOrder.nativeOrder())
                        if (floatPcm) {
                            val fb = bb.asFloatBuffer()
                            push(fb.remaining() / channels) { fb.get(it) }
                        } else {
                            val sb = bb.asShortBuffer()
                            push(sb.remaining() / channels) { sb.get(it) / 32768f }
                        }
                    }
                    if (durUs > 0) progress = (info.presentationTimeUs.toFloat() / durUs).coerceIn(0f, 1f)
                    c.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            if (!stopped && filled > RATE / 2) onChunk(chunk.copyOf(filled), chunkStartMs, 1f)
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            codec?.release()
            ex.release()
        }
    }
}
