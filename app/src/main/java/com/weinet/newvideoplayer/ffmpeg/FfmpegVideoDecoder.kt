package com.weinet.newvideoplayer.ffmpeg

import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import java.nio.ByteBuffer

/**
 * Software video decoder on FFmpeg's libavcodec (H.264, HEVC, VP8, VP9, MPEG-4, MPEG-2, H.263)
 * and dav1d (AV1), with its own decode thread.
 *
 * Unlike Media3's SimpleDecoder (one output per input), inputs and outputs are decoupled: FFmpeg
 * holds frames back for B-frame reordering and frame threading, so one input can yield zero or
 * several frames. At end of stream FFmpeg is drained, so the last frames of a video are shown too.
 */
@UnstableApi
class FfmpegVideoDecoder(
    format: Format,
    numInputBuffers: Int,
    numOutputBuffers: Int,
    initialInputBufferSize: Int,
    threads: Int,
) : Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException> {

    private val codecName: String
    private val context: Long
    private val lock = Object()
    private val info = LongArray(3)

    private val availableInputs = ArrayDeque<DecoderInputBuffer>()
    private val queuedInputs = ArrayDeque<DecoderInputBuffer>()
    private val availableOutputs = ArrayDeque<VideoDecoderOutputBuffer>()
    private val queuedOutputs = ArrayDeque<VideoDecoderOutputBuffer>()
    private var dequeuedInput: DecoderInputBuffer? = null
    private var exception: FfmpegDecoderException? = null
    private var flushed = false
    private var released = false
    private var outputStartTimeUs = C.TIME_UNSET
    private var skippedOutputs = 0

    // Decode-thread state.
    private var framesReady = 0
    private var draining = false
    private var lastFormat: Format? = null

    @Volatile var outputMode: Int = C.VIDEO_OUTPUT_MODE_NONE
    @Volatile private var closed = false
    private val thread: Thread

    init {
        if (!FfmpegLibrary.isAvailable()) throw FfmpegDecoderException("Failed to load FFmpeg")
        codecName = FfmpegLibrary.getCodecName(format.sampleMimeType ?: "")
            ?: throw FfmpegDecoderException("Unsupported video: ${format.sampleMimeType}")
        val extra = format.initializationData.takeIf { it.isNotEmpty() }?.let { list ->
            val out = ByteArray(list.sumOf { it.size })
            var pos = 0
            for (b in list) { System.arraycopy(b, 0, out, pos, b.size); pos += b.size }
            out
        }
        context = nativeInit(codecName, extra, threads)
        if (context == 0L) throw FfmpegDecoderException("Failed to open FFmpeg $codecName decoder")
        val padding = FfmpegLibrary.getInputBufferPaddingSize()
        repeat(numInputBuffers) {
            availableInputs.addLast(
                DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, padding).apply {
                    ensureSpaceForWrite(initialInputBufferSize)
                },
            )
        }
        repeat(numOutputBuffers) { availableOutputs.addLast(VideoDecoderOutputBuffer { releaseOutput(it) }) }
        thread = Thread({ runLoop() }, "FfmpegVideoDecoder").apply { start() }
    }

    override fun getName(): String = "ffmpeg-$codecName"

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        synchronized(lock) { this.outputStartTimeUs = outputStartTimeUs }
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? = synchronized(lock) {
        throwIfFailed()
        check(dequeuedInput == null)
        dequeuedInput = availableInputs.removeLastOrNull()
        dequeuedInput
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) = synchronized(lock) {
        throwIfFailed()
        require(inputBuffer === dequeuedInput)
        queuedInputs.addLast(inputBuffer)
        dequeuedInput = null
        lock.notifyAll()
    }

    override fun dequeueOutputBuffer(): VideoDecoderOutputBuffer? = synchronized(lock) {
        throwIfFailed()
        queuedOutputs.removeFirstOrNull()
    }

    override fun flush() = synchronized(lock) {
        flushed = true
        skippedOutputs = 0
        dequeuedInput?.let { recycleInput(it) }
        dequeuedInput = null
        while (queuedInputs.isNotEmpty()) recycleInput(queuedInputs.removeFirst())
        while (queuedOutputs.isNotEmpty()) queuedOutputs.removeFirst().release()
        lock.notifyAll()
    }

    override fun release() {
        synchronized(lock) {
            released = true
            lock.notifyAll()
        }
        try { thread.join() } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        closed = true
        nativeClose(context)
    }

    /** Draws [outputBuffer] into [surface] (VIDEO_OUTPUT_MODE_SURFACE_YUV). */
    fun renderToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        if (closed || nativeRender(context, surface, outputBuffer.decoderPrivate.toInt()) != 0) {
            throw FfmpegDecoderException("Failed to render frame")
        }
    }

    private fun releaseOutput(buffer: VideoDecoderOutputBuffer) {
        val id = buffer.decoderPrivate.toInt()
        if (id != 0 && !closed) nativeReleaseFrame(context, id)
        buffer.decoderPrivate = 0L
        synchronized(lock) {
            buffer.clear()
            availableOutputs.addLast(buffer)
            lock.notifyAll()
        }
    }

    private fun recycleInput(buffer: DecoderInputBuffer) {
        buffer.clear()
        availableInputs.addLast(buffer)
    }

    private fun throwIfFailed() {
        exception?.let { throw it }
    }

    private fun canWork(): Boolean =
        flushed || (availableOutputs.isNotEmpty() && (framesReady > 0 || draining || queuedInputs.isNotEmpty()))

    private fun runLoop() {
        try {
            while (step()) { /* keep going */ }
        } catch (_: InterruptedException) {
            // Released.
        } catch (e: Throwable) {
            synchronized(lock) { exception = FfmpegDecoderException("Unexpected decode error", e) }
        }
    }

    /** One unit of work. Returns false when the decoder is released or has failed. */
    private fun step(): Boolean {
        var input: DecoderInputBuffer? = null
        val output: VideoDecoderOutputBuffer
        synchronized(lock) {
            while (!released && !canWork()) lock.wait()
            if (released) return false
            if (flushed) {
                flushed = false
                nativeFlush(context)
                framesReady = 0
                draining = false
                return true
            }
            output = availableOutputs.removeLast()
            if (framesReady == 0 && !draining) input = queuedInputs.removeFirst()
        }

        when {
            framesReady > 0 -> {
                val id = nativeDequeue(context, info)
                framesReady--
                if (id == 0) { recycleOutput(output); return true }
                val timeUs = info[0]
                if (!startReached(timeUs)) {
                    nativeReleaseFrame(context, id)
                    synchronized(lock) { skippedOutputs++ }
                    recycleOutput(output)
                    return true
                }
                output.init(timeUs, outputMode, null)
                output.initForPrivateFrame(info[1].toInt(), info[2].toInt())
                output.decoderPrivate = id.toLong()
                output.format = lastFormat
                queueOutput(output)
            }
            draining -> {
                // Everything FFmpeg held back has been handed out: signal end of stream.
                draining = false
                output.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
                queueOutput(output)
            }
            else -> {
                val buf = input!!
                recycleOutput(output)
                if (buf.isEndOfStream) {
                    val n = nativeSendEos(context)
                    synchronized(lock) { recycleInput(buf) }
                    if (n < 0) return fail("FFmpeg drain error $n")
                    framesReady = n
                    draining = true
                } else {
                    if (buf.format != null) lastFormat = buf.format
                    val data = buf.data
                    val n = if (data == null) 0 else nativeDecode(context, data, data.limit(), buf.timeUs)
                    synchronized(lock) { recycleInput(buf) }
                    if (n < 0) return fail("FFmpeg decode error $n")
                    framesReady = n
                }
            }
        }
        return true
    }

    private fun startReached(timeUs: Long) = synchronized(lock) {
        outputStartTimeUs == C.TIME_UNSET || timeUs >= outputStartTimeUs
    }

    private fun recycleOutput(output: VideoDecoderOutputBuffer) = synchronized(lock) {
        output.clear()
        availableOutputs.addLast(output)
    }

    private fun queueOutput(output: VideoDecoderOutputBuffer) {
        synchronized(lock) {
            if (!flushed) {
                output.skippedOutputBufferCount = skippedOutputs
                skippedOutputs = 0
                queuedOutputs.addLast(output)
                return
            }
        }
        output.release() // A flush arrived while decoding: drop this frame.
    }

    private fun fail(message: String): Boolean {
        synchronized(lock) { exception = FfmpegDecoderException(message) }
        return false
    }

    private companion object {
        @JvmStatic external fun nativeInit(codecName: String, extraData: ByteArray?, threads: Int): Long
        /** Returns the number of frames ready to dequeue, or a negative AVERROR. */
        @JvmStatic external fun nativeDecode(context: Long, data: ByteBuffer, size: Int, timeUs: Long): Int
        /** Signals end of stream and returns the number of frames FFmpeg still had. */
        @JvmStatic external fun nativeSendEos(context: Long): Int
        @JvmStatic external fun nativeFlush(context: Long)
        @JvmStatic external fun nativeDequeue(context: Long, info: LongArray): Int
        @JvmStatic external fun nativeReleaseFrame(context: Long, id: Int)
        @JvmStatic external fun nativeRender(context: Long, surface: Surface, id: Int): Int
        @JvmStatic external fun nativeClose(context: Long)
    }
}
