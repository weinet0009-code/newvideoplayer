package com.weinet.newvideoplayer.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Pauses playback after a number of minutes, or at the end of the current video. */
object SleepTimer {
    /** Minutes offered in the menu; 0 means "when this video ends". */
    val OPTIONS = listOf(15, 30, 45, 60, 90, 0)

    sealed interface State {
        data object Off : State
        data class At(val epochMs: Long) : State
        data object EndOfVideo : State
    }

    private val _state = MutableStateFlow<State>(State.Off)
    val state: StateFlow<State> = _state
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    private val fire = Runnable { player?.pause(); cancel() }

    private val endListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) cancel()
        }
    }

    fun set(p: ExoPlayer, minutes: Int) {
        cancel()
        player = p
        if (minutes == 0) {
            p.pauseAtEndOfMediaItems = true
            p.addListener(endListener)
            _state.value = State.EndOfVideo
        } else {
            val ms = minutes * 60_000L
            handler.postDelayed(fire, ms)
            _state.value = State.At(System.currentTimeMillis() + ms)
        }
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        player?.let { it.pauseAtEndOfMediaItems = false; it.removeListener(endListener) }
        player = null
        _state.value = State.Off
    }
}
