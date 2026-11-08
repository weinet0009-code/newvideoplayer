package com.weinet.newvideoplayer.ffmpeg

import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Video renderer that decodes with [FfmpegVideoDecoder] and draws into the player's Surface. */
@UnstableApi
class FfmpegVideoRenderer(
    allowedJoiningTimeMs: Long,
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    maxDroppedFramesToNotify: Int,
) : DecoderVideoRenderer(allowedJoiningTimeMs, eventHandler, eventListener, maxDroppedFramesToNotify) {

    private var decoder: FfmpegVideoDecoder? = null

    override fun getName(): String = "FfmpegVideoRenderer"

    override fun supportsFormat(format: Format): Int {
        val mime = format.sampleMimeType
        if (mime == null || !MimeTypes.isVideo(mime) || !FfmpegLibrary.isAvailable()) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        }
        if (!FfmpegLibrary.supportsFormat(mime)) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        return RendererCapabilities.create(
            C.FORMAT_HANDLED,
            RendererCapabilities.ADAPTIVE_NOT_SEAMLESS,
            RendererCapabilities.TUNNELING_NOT_SUPPORTED,
        )
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): FfmpegVideoDecoder {
        val inputSize = if (format.maxInputSize != Format.NO_VALUE) format.maxInputSize else 1024 * 1024
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
        return FfmpegVideoDecoder(format, 8, 8, inputSize, threads).also { decoder = it }
    }

    override fun renderOutputBufferToSurface(outputBuffer: VideoDecoderOutputBuffer, surface: Surface) {
        val d = decoder ?: throw FfmpegDecoderException("Decoder not initialized")
        d.renderToSurface(outputBuffer, surface)
        outputBuffer.release()
    }

    override fun setDecoderOutputMode(outputMode: Int) {
        decoder?.outputMode = outputMode
    }
}
