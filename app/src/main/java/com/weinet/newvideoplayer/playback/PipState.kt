package com.weinet.newvideoplayer.playback

import androidx.compose.runtime.mutableStateOf

/** Picture-in-picture state shared between MainActivity and the player screen. */
object PipState {
    val inPip = mutableStateOf(false)

    /** Set by the player screen; MainActivity calls it when the user presses Home (pre-Android 12). */
    var onUserLeave: (() -> Unit)? = null
}
