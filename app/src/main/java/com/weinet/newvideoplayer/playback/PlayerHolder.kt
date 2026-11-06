package com.weinet.newvideoplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.weinet.newvideoplayer.vault.AppDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.weinet.newvideoplayer.data.LibraryStore
import com.weinet.newvideoplayer.data.PlaybackPositionStore
import com.weinet.newvideoplayer.data.PlayerSettings
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.toMediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the one ExoPlayer the app uses, so the player screen, picture-in-picture,
 * background playback and the floating window all share the same playback.
 */
object PlayerHolder {
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player

    /** The queue now playing (a folder, the recent list, or a playlist). */
    var queue: List<Video> = emptyList()
        private set

    /** True while playback lives in the floating window instead of the player screen. */
    var floating = false

    private var controller: ListenableFuture<MediaController>? = null

    @OptIn(UnstableApi::class)
    private fun build(context: Context): ExoPlayer {
        val app = context.applicationContext
        val preferSoftware = PlayerSettings(app).preferSoftwareDecoder
        val selector = MediaCodecSelector { mime, secure, tunneling ->
            val all = MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            if (preferSoftware) all.sortedBy { !it.softwareOnly } else all.sortedBy { it.softwareOnly }
        }
        val renderers = DefaultRenderersFactory(app)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val positions = PlaybackPositionStore(app)
        return ExoPlayer.Builder(app, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(AppDataSource.Factory(app)))
            .setHandleAudioBecomingNoisy(true)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        val id = item?.mediaId?.toLongOrNull() ?: return
                        LibraryStore.addRecent(id)
                        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                            reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK) {
                            val resume = positions.get(id)
                            if (resume > 0) seekTo(resume)
                        }
                    }
                })
            }
    }

    private fun obtain(context: Context): ExoPlayer =
        _player.value ?: build(context).also { _player.value = it }

    /** Starts playing [videos] at [index], resuming where that video was left off. */
    fun start(context: Context, videos: List<Video>, index: Int) {
        val app = context.applicationContext
        saveCurrentPosition(app)
        val p = obtain(app)
        queue = videos
        p.setMediaItems(videos.map { it.toMediaItem() }, index, PlaybackPositionStore(app).get(videos[index].id))
        p.setPlaybackSpeed(PlayerSettings(app).speed)
        p.prepare()
        p.playWhenReady = true
        LibraryStore.addRecent(videos[index].id)
        connectSession(app)
    }

    /** Recreates the player (e.g. after switching decoders) keeping queue and position. */
    fun rebuild(context: Context) {
        val app = context.applicationContext
        val old = _player.value ?: return
        val items = List(old.mediaItemCount) { old.getMediaItemAt(it) }
        val index = old.currentMediaItemIndex
        val pos = old.currentPosition
        val play = old.playWhenReady
        val speed = old.playbackParameters.speed
        old.release()
        val p = build(app)
        p.setMediaItems(items, index, pos)
        p.setPlaybackSpeed(speed)
        p.prepare()
        p.playWhenReady = play
        _player.value = p
    }

    fun saveCurrentPosition(context: Context) {
        val p = _player.value ?: return
        val id = p.currentMediaItem?.mediaId?.toLongOrNull() ?: return
        PlaybackPositionStore(context.applicationContext).save(id, p.currentPosition, p.duration)
    }

    /** Stops playback and ends the background session. */
    fun stop(context: Context) {
        saveCurrentPosition(context)
        floating = false
        _player.value?.let { it.stop(); it.clearMediaItems() }
        controller?.let { MediaController.releaseFuture(it) }
        controller = null
    }

    fun releaseAll() {
        _player.value?.release()
        _player.value = null
        queue = emptyList()
    }

    // Connecting a controller lets the MediaSessionService run in the foreground with a notification.
    private fun connectSession(context: Context) {
        if (controller != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controller = MediaController.Builder(context, token).buildAsync()
    }
}
