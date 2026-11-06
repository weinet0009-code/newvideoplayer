package com.weinet.newvideoplayer.ui.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

@OptIn(UnstableApi::class)
fun buildPlayer(context: Context, preferSoftware: Boolean): ExoPlayer {
    val selector = MediaCodecSelector { mime, secure, tunneling ->
        val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
        if (preferSoftware) all.sortedBy { !it.softwareOnly } else all.sortedBy { it.softwareOnly }
    }
    val renderers = DefaultRenderersFactory(context)
        .setMediaCodecSelector(selector)
        .setEnableDecoderFallback(true)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
    return ExoPlayer.Builder(context, renderers).build()
}
