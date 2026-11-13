package com.weinet.newvideoplayer.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * 10-band peaking equalizer (RBJ biquads, about one octave wide) with an automatic pre-amp so
 * boosts don't clip, optional stereo widening (mid/side) and a night-mode compressor.
 * Works on 16-bit and float PCM; other formats pass by untouched.
 */
@UnstableApi
class EqAudioProcessor : BaseAudioProcessor() {
    private var channels = 0
    private var rate = 0
    private var isFloat = false
    private var version = -1
    private var active = false
    private var bands = 0
    // Coefficients per band: b0 b1 b2 a1 a2 (normalized).
    private var coef = FloatArray(0)
    // State per channel per band: x1 x2 y1 y2.
    private var st = FloatArray(0)
    private var preamp = 1f
    private var widen = 0f
    private var compress = false
    private var env = 0f
    private var atk = 0f
    private var rel = 0f

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val enc = inputAudioFormat.encoding
        if (enc != C.ENCODING_PCM_16BIT && enc != C.ENCODING_PCM_FLOAT) return AudioFormat.NOT_SET
        channels = inputAudioFormat.channelCount
        rate = inputAudioFormat.sampleRate
        isFloat = enc == C.ENCODING_PCM_FLOAT
        version = -1
        return inputAudioFormat
    }

    override fun onFlush() { st.fill(0f); env = 0f }
    override fun onReset() { channels = 0; rate = 0; version = -1; st = FloatArray(0) }

    private fun update(p: AudioEq.Params) {
        version = p.version
        active = p.active
        val nyq = rate / 2f
        val list = AudioEq.BANDS_HZ.indices.filter { AudioEq.BANDS_HZ[it] < nyq * 0.9f && p.gains[it] != 0f }
        val newBands = list.size
        val c = FloatArray(newBands * 5)
        list.forEachIndexed { k, i ->
            val a = 10.0.pow(p.gains[i] / 40.0)
            val w0 = 2 * PI * AudioEq.BANDS_HZ[i] / rate
            val alpha = sin(w0) / (2 * 1.41)
            val a0 = 1 + alpha / a
            c[k * 5] = ((1 + alpha * a) / a0).toFloat()
            c[k * 5 + 1] = (-2 * cos(w0) / a0).toFloat()
            c[k * 5 + 2] = ((1 - alpha * a) / a0).toFloat()
            c[k * 5 + 3] = (-2 * cos(w0) / a0).toFloat()
            c[k * 5 + 4] = ((1 - alpha / a) / a0).toFloat()
        }
        if (newBands != bands || st.size != newBands * channels * 4) st = FloatArray(newBands * channels * 4)
        bands = newBands; coef = c
        preamp = 10f.pow(p.preampDb / 20f)
        widen = if (channels == 2) p.widen else 0f
        compress = p.compress
        atk = exp(-1f / (0.005f * rate))
        rel = exp(-1f / (0.25f * rate))
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val p = AudioEq.params
        if (p.version != version) update(p)
        val out = replaceOutputBuffer(size)
        if (!active) { out.put(inputBuffer); out.flip(); return }
        val inB = inputBuffer.order(ByteOrder.nativeOrder())
        val bytes = if (isFloat) 4 else 2
        val frames = size / (bytes * channels)
        val frame = FloatArray(channels)
        for (n in 0 until frames) {
            for (ch in 0 until channels) {
                frame[ch] = if (isFloat) inB.getFloat() else inB.getShort() / 32768f
            }
            if (widen > 0f) {
                val m = (frame[0] + frame[1]) * 0.5f
                val s = (frame[0] - frame[1]) * 0.5f * (1f + widen)
                frame[0] = m + s; frame[1] = m - s
            }
            var peak = 0f
            for (ch in 0 until channels) {
                var x = frame[ch] * preamp
                var b = 0
                while (b < bands) {
                    val ci = b * 5; val si = (ch * bands + b) * 4
                    val y = coef[ci] * x + coef[ci + 1] * st[si] + coef[ci + 2] * st[si + 1] - coef[ci + 3] * st[si + 2] - coef[ci + 4] * st[si + 3]
                    st[si + 1] = st[si]; st[si] = x; st[si + 3] = st[si + 2]; st[si + 2] = y
                    x = y; b++
                }
                frame[ch] = x
                peak = max(peak, abs(x))
            }
            if (compress) {
                // Threshold -24 dBFS, ratio 4:1, +10 dB make-up: quiet speech up, explosions down.
                env = if (peak > env) atk * env + (1 - atk) * peak else rel * env + (1 - rel) * peak
                val db = 20f * log10(max(env, 1e-6f))
                val over = db + 24f
                val gainDb = (if (over > 0f) -over * 0.75f else 0f) + 10f
                val g = 10f.pow(gainDb / 20f)
                for (ch in 0 until channels) frame[ch] *= g
            }
            for (ch in 0 until channels) {
                // Soft clip only near full scale.
                var v = frame[ch]
                if (abs(v) > 0.9f) v = tanh(v)
                if (isFloat) out.putFloat(v) else out.putShort((v.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            }
        }
        // Leftover partial frame bytes (shouldn't happen) are dropped.
        inputBuffer.position(inputBuffer.limit())
        out.flip()
    }
}
