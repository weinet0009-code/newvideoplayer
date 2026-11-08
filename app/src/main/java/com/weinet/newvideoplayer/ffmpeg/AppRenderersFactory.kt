package com.weinet.newvideoplayer.ffmpeg

import android.content.Context
import android.os.Handler
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener

/**
 * Adds the app's own FFmpeg renderers. EXTENSION_RENDERER_MODE_ON: used only when the phone's
 * decoders can't handle a track. PREFER: FFmpeg video is tried first (the "software decoder" setting).
 */
@UnstableApi
class AppRenderersFactory(context: Context) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            eventHandler, eventListener, allowedVideoJoiningTimeMs, out,
        )
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF || !FfmpegLibrary.isAvailable()) return
        val r = FfmpegVideoRenderer(allowedVideoJoiningTimeMs, eventHandler, eventListener, 50)
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER) out.add(0, r) else out.add(r)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>,
    ) {
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
            audioSink, eventHandler, eventListener, out,
        )
        if (extensionRendererMode == EXTENSION_RENDERER_MODE_OFF || !FfmpegLibrary.isAvailable()) return
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
    }
}
