package com.weinet.newvideoplayer.playback

import android.content.Context
import com.weinet.newvideoplayer.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Equalizer settings shared by every player. [EqAudioProcessor] reads [params] for each buffer, so
 * changes apply right away without rebuilding the player. Not applied while casting (the TV decodes).
 */
object AudioEq {
    val BANDS_HZ = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
    const val MAX_DB = 12f

    /** A scene: gains in dB per band, stereo widening (0 = none), night-mode compression. */
    class Preset(val key: String, val nameRes: Int, val gains: FloatArray, val widen: Float = 0f, val compress: Boolean = false)

    val PRESETS = listOf(
        Preset("flat", R.string.eq_flat, f(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
        Preset("theater", R.string.eq_theater, f(4, 4, 2, 0, -1, 0, 2, 3, 3, 2), widen = 0.35f),
        Preset("voice", R.string.eq_voice, f(-2, -2, -1, 0, 1, 3, 4, 3, 1, 0)),
        Preset("night", R.string.eq_night, f(-3, -2, -1, 0, 0, 1, 2, 1, 0, 0), compress = true),
        Preset("pop", R.string.eq_pop, f(-1, 0, 2, 3, 2, 0, -1, -1, 0, 1)),
        Preset("rock", R.string.eq_rock, f(4, 3, 2, 0, -2, -1, 1, 3, 4, 4)),
        Preset("jazz", R.string.eq_jazz, f(3, 2, 1, 2, -1, -1, 0, 1, 2, 3)),
        Preset("blues", R.string.eq_blues, f(2, 3, 3, 2, 0, -1, 1, 2, 1, 0)),
        Preset("classical", R.string.eq_classical, f(3, 2, 1, 0, 0, 0, -1, 1, 2, 3)),
        Preset("bass", R.string.eq_bass, f(6, 5, 4, 2, 0, 0, 0, 0, 0, 0)),
    )
    const val CUSTOM = "custom"

    data class State(
        val enabled: Boolean = false,
        val preset: String = "flat",
        val custom: FloatArray = FloatArray(10),
        /** true = pre-amp follows [suggestedPreamp]; false = [preamp] set by the user (dB). */
        val autoPreamp: Boolean = true,
        val preamp: Float = 0f,
    ) {
        val gains: FloatArray get() = if (preset == CUSTOM) custom else (PRESETS.firstOrNull { it.key == preset } ?: PRESETS[0]).gains
        val widen: Float get() = PRESETS.firstOrNull { it.key == preset }?.widen ?: 0f
        /** The pre-amp in effect, in dB. */
        val effectivePreamp: Float get() = if (autoPreamp) suggestedPreamp(gains, widen) else preamp
    }

    /** Enough cut that the biggest boost (and stereo widening) can't push past full scale. */
    fun suggestedPreamp(gains: FloatArray, widen: Float): Float {
        val boost = maxOf(0f, gains.maxOrNull() ?: 0f)
        val w = if (widen > 0f) 20f * kotlin.math.log10(1f + widen / 2f) else 0f
        return -Math.round(boost + w).toFloat()
    }

    /** Pick a scene; with a manual pre-amp, it takes that scene's suggested value. */
    fun choosePreset(s: State, key: String): State {
        val n = s.copy(preset = key)
        return if (n.autoPreamp) n else n.copy(preamp = suggestedPreamp(n.gains, n.widen))
    }

    /** What the processor applies. [version] changes whenever anything does. */
    class Params(val active: Boolean, val gains: FloatArray, val preampDb: Float, val widen: Float, val compress: Boolean, val version: Int)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    @Volatile var params = Params(false, FloatArray(10), 0f, 0f, false, 0)
        private set
    private var prefsCtx: Context? = null

    fun init(context: Context) {
        val p = context.getSharedPreferences("eq", Context.MODE_PRIVATE)
        prefsCtx = context.applicationContext
        val custom = FloatArray(10)
        runCatching { JSONArray(p.getString("custom", "[]")).let { a -> for (i in 0 until minOf(10, a.length())) custom[i] = a.getDouble(i).toFloat() } }
        set(State(p.getBoolean("enabled", false), p.getString("preset", "flat") ?: "flat", custom,
            p.getBoolean("auto_preamp", true), p.getFloat("preamp", 0f)), save = false)
    }

    fun set(s: State, save: Boolean = true) {
        _state.value = s
        val preset = PRESETS.firstOrNull { it.key == s.preset }
        val gains = s.gains.copyOf()
        val widen = preset?.widen ?: 0f
        val compress = preset?.compress ?: false
        val pre = s.effectivePreamp
        val active = s.enabled && (gains.any { it != 0f } || widen > 0f || compress || pre != 0f)
        params = Params(active, gains, pre, widen, compress, params.version + 1)
        if (save) prefsCtx?.getSharedPreferences("eq", Context.MODE_PRIVATE)?.edit()
            ?.putBoolean("enabled", s.enabled)?.putString("preset", s.preset)
            ?.putBoolean("auto_preamp", s.autoPreamp)?.putFloat("preamp", s.preamp)
            ?.putString("custom", JSONArray(s.custom.map { it.toDouble() }).toString())?.apply()
    }

    fun nameRes(key: String): Int = PRESETS.firstOrNull { it.key == key }?.nameRes ?: R.string.eq_custom

    private fun f(vararg v: Int) = FloatArray(v.size) { v[it].toFloat() }
}
