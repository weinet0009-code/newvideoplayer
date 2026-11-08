package com.weinet.newvideoplayer.ffmpeg

import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer

/**
 * Software video decoder on FFmpeg's libavcodec (H.264, HEVC, VP8, VP9, MPEG-4, MPEG-2, H.263)
 * and dav1d (AV1). FFmpeg may hold frames back for reordering, so an input can produce no
 * output (skipped) and a later one returns the delayed frame.
 */
@UnstableApi
class FfmpegVideoDecoder(
    format: Format,
    numInputBuffers: Int,
    numOutputBuffers: Int,
    initialInputBufferSize: Int,
    threads: Int,
) : SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfmpegDecoderException>(
    @Suppress("UNCHECKED_CAST") (arrayOfNulls<DecoderInputBuffer>(numInputBuffers) as Array<DecoderInputBuffer>),
    @Suppress("UNCHECKED_CAST") (arrayOfNulls<VideoDecoderOutputBuffer>(numOutputBuffers) as Array<VideoDecoderOutputBuffer>),
) {
    private val codecName: String
    private val context: Long
    private val info = LongArray(3)

    @Volatile var outputMode: Int = C.VIDEO_OUTPUT_MODE_NONE
    @Volatile private var closed = false

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
        setInitialInputBufferSize(initialInputBufferSize)
    }

    override fun getName(): String = "ffmpeg-$codecName"

    override fun createInputBuffer() = DecoderInputBuffer(
        DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, FfmpegLibrary.getInputBufferPaddingSize(),
    )

    override fun createOutputBuffer() = VideoDecoderOutputBuffer { releaseOutputBuffer(it) }

    override fun releaseOutputBuffer(outputBuffer: VideoDecoderOutputBuffer) {
        val id = outputBuffer.decoderPrivate.toInt()
        if (id != 0 && !closed) {
            nativeReleaseFrame(context, id)
            outputBuffer.decoderPrivate = 0L
        }
        super.releaseOutputBuffer(outputBuffer)
    }

    override fun createUnexpectedDecodeException(error: Throwable) =
        FfmpegDecoderException("Unexpected decode error", error)

    override fun decode(
        inputBuffer: DecoderInputBuffer,
        outputBuffer: VideoDecoderOutputBuffer,
        reset: Boolean,
    ): FfmpegDecoderException? {
        if (reset) nativeFlush(context)
        val data = inputBuffer.data ?: return FfmpegDecoderException("No input data")
        val r = nativeDecode(context, data, data.limit(), inputBuffer.timeUs)
        if (r < 0) return FfmpegDecoderException("FFmpeg decode error $r")
        val id = nativeDequeue(context, info)
        if (id == 0) {
            outputBuffer.shouldBeSkipped = true
            return null
        }
        val timeUs = if (info[0] == C.TIME_UNSET || info[0] < 0) inputBuffer.timeUs else info[0]
        if (!isAtLeastOutputStartTimeUs(timeUs)) {
            nativeReleaseFrame(context, id)
            outputBuffer.shouldBeSkipped = true
            return null
        }
        outputBuffer.init(timeUs, outputMode, null)
        outputBuffer.initForPrivateFrame(info[1].toInt(), info[2].toInt())
        outputBuffer.decoderPrivate = id.toLong()
        outputBuffer.format = inputBuffer.format
        return null
    }

    /** Draws [outputBuffer] into [surface] (VIDEO_OUTPUT_MODE_SURFACE_YUV). */
    fun renderToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        if (closed || nativeRender(context, surface, outputBuffer.decoderPrivate.toInt()) != 0) {
            throw FfmpegDecoderException("Failed to render frame")
        }
    }

    override fun release() {
        super.release()
        closed = true
        nativeClose(context)
    }

    private companion object {
        @JvmStatic external fun nativeInit(codecName: String, extraData: ByteArray?, threads: Int): Long
        @JvmStatic external fun nativeDecode(context: Long, data: java.nio.ByteBuffer, size: Int, timeUs: Long): Int
        @JvmStatic external fun nativeFlush(context: Long)
        @JvmStatic external fun nativeDequeue(context: Long, info: LongArray): Int
        @JvmStatic external fun nativeReleaseFrame(context: Long, id: Int)
        @JvmStatic external fun nativeRender(context: Long, surface: Surface, id: Int): Int
        @JvmStatic external fun nativeClose(context: Long)
    }
}
