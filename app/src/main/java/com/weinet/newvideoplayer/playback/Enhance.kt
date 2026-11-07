package com.weinet.newvideoplayer.playback

import android.content.Context
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.SingleFrameGlShaderProgram

/** Picture enhancement levels: off, light, strong. Runs on the GPU through Media3 effects. */
object Enhance {
    val LABELS = listOf("關閉", "輕度", "強")

    @OptIn(UnstableApi::class)
    fun effects(level: Int): List<Effect> = when (level) {
        1 -> listOf(Contrast(0.08f), HslAdjustment.Builder().adjustSaturation(10f).build(), Sharpen(0.35f))
        2 -> listOf(Contrast(0.15f), HslAdjustment.Builder().adjustSaturation(20f).build(), Sharpen(0.7f))
        else -> emptyList()
    }
}

/** Unsharp-mask style sharpening: adds back the difference from the 4 neighbouring pixels. */
@UnstableApi
class Sharpen(private val amount: Float) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(amount, useHdr)

    private class Program(private val amount: Float, useHdr: Boolean) :
        SingleFrameGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr) {
        private val program = GlProgram(VERTEX, FRAGMENT).apply {
            setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        }
        private var w = 1
        private var h = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            w = inputWidth; h = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uTexelSize", floatArrayOf(1f / w, 1f / h))
                program.setFloatUniform("uAmount", amount)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try { program.delete() } catch (_: GlUtil.GlException) {}
        }
    }

    private companion object {
        const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexCoords;
void main() {
  gl_Position = aFramePosition;
  vTexCoords = (aFramePosition.xy + 1.0) * 0.5;
}
"""
        const val FRAGMENT = """
precision mediump float;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform float uAmount;
varying vec2 vTexCoords;
void main() {
  vec4 c = texture2D(uTexSampler, vTexCoords);
  vec3 n = texture2D(uTexSampler, vTexCoords + vec2(uTexelSize.x, 0.0)).rgb
         + texture2D(uTexSampler, vTexCoords - vec2(uTexelSize.x, 0.0)).rgb
         + texture2D(uTexSampler, vTexCoords + vec2(0.0, uTexelSize.y)).rgb
         + texture2D(uTexSampler, vTexCoords - vec2(0.0, uTexelSize.y)).rgb;
  gl_FragColor = vec4(clamp(c.rgb + uAmount * (4.0 * c.rgb - n), 0.0, 1.0), c.a);
}
"""
    }
}
