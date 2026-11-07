package com.weinet.newvideoplayer.data

import android.content.Context

/** Player preferences that carry over between videos. */
class PlayerSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("player_settings", Context.MODE_PRIVATE)

    /** true = prefer software decoders (more formats, more battery); false = hardware acceleration. */
    var preferSoftwareDecoder: Boolean
        get() = prefs.getBoolean("sw_decoder", false)
        set(v) = prefs.edit().putBoolean("sw_decoder", v).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(v) = prefs.edit().putFloat("speed", v).apply()

    /** Keep playing audio with a notification when the app leaves the screen. */
    var backgroundPlay: Boolean
        get() = prefs.getBoolean("background_play", false)
        set(v) = prefs.edit().putBoolean("background_play", v).apply()

    /** Switch to picture-in-picture when the user presses Home while a video plays. */
    var autoPip: Boolean
        get() = prefs.getBoolean("auto_pip", true)
        set(v) = prefs.edit().putBoolean("auto_pip", v).apply()

    /** Player.REPEAT_MODE_OFF / ONE / ALL. */
    var repeatMode: Int
        get() = prefs.getInt("repeat_mode", 0)
        set(v) = prefs.edit().putInt("repeat_mode", v).apply()

    /** Picture enhancement level, 0 = off (see playback.Enhance). */
    var enhance: Int
        get() = prefs.getInt("enhance", 0)
        set(v) = prefs.edit().putInt("enhance", v).apply()
}
