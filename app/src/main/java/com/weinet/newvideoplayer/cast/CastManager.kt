package com.weinet.newvideoplayer.cast

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastContext
import com.weinet.newvideoplayer.playback.PlayerHolder
import kotlinx.coroutines.flow.MutableStateFlow

/** Hands the current queue to a Chromecast and back to the phone when the session ends. */
@OptIn(UnstableApi::class)
object CastManager {
    private var castContext: CastContext? = null
    var castPlayer: CastPlayer? = null
        private set

    val active = MutableStateFlow(false)
    val deviceName = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    val selector: MediaRouteSelector
        get() = MediaRouteSelector.Builder()
            .addControlCategory(CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID))
            .build()

    /** False when Google Play services (and so Cast) is unavailable. Call on the main thread. */
    @Suppress("DEPRECATION")
    fun init(context: Context): Boolean {
        if (castContext != null) return true
        val app = context.applicationContext
        return try {
            val cc = CastContext.getSharedInstance(app)
            castContext = cc
            castPlayer = CastPlayer(cc).apply {
                setSessionAvailabilityListener(object : SessionAvailabilityListener {
                    override fun onCastSessionAvailable() = startCasting(app)
                    override fun onCastSessionUnavailable() = stopCasting()
                })
            }
            if (castPlayer!!.isCastSessionAvailable) startCasting(app)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startCasting(app: Context) {
        val cp = castPlayer ?: return
        val local = PlayerHolder.player.value
        val videos = PlayerHolder.queue
        deviceName.value = castContext?.sessionManager?.currentCastSession?.castDevice?.friendlyName
        if (local == null || videos.isEmpty()) return
        val urls = LocalMediaServer.serve(app, videos)
        if (urls == null) { error.value = "手機沒有連上 Wi-Fi"; endSession(); return }
        val index = local.currentMediaItemIndex.coerceIn(0, videos.size - 1)
        val position = local.currentPosition
        local.pause()
        val items = videos.mapIndexed { i, v ->
            MediaItem.Builder()
                .setUri(urls[i])
                .setMimeType(v.mimeType.takeIf { it != "video/*" } ?: "video/mp4")
                .setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).build())
                .build()
        }
        cp.setMediaItems(items, index, position)
        cp.playWhenReady = true
        active.value = true
    }

    private fun stopCasting() {
        val cp = castPlayer
        val local = PlayerHolder.player.value
        if (active.value && cp != null && local != null && local.mediaItemCount > 0) {
            val idx = cp.currentMediaItemIndex.coerceIn(0, local.mediaItemCount - 1)
            local.seekTo(idx, cp.currentPosition)
        }
        active.value = false
        deviceName.value = null
        LocalMediaServer.stop()
    }

    fun endSession() {
        castContext?.sessionManager?.endCurrentSession(true)
    }
}
