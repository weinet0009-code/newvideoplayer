package com.weinet.newvideoplayer.playback

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How subtitles look in the player and the floating window. Saved in prefs "sub_style". */
object SubtitleStyle {
    val COLORS = intArrayOf(Color.WHITE, 0xFFFFEB3B.toInt(), 0xFF80DEEA.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFAB91.toInt(), 0xFFCE93D8.toInt(), Color.BLACK)
    enum class Edge { None, Outline, Shadow }
    enum class Background { None, Box }

    data class Style(
        val scale: Float = 1f,          // 0.5–2.0 × the default size
        val color: Int = Color.WHITE,
        val bold: Boolean = false,
        val edge: Edge = Edge.Outline,
        val background: Background = Background.None,
        val bottom: Float = 0.08f,      // distance from the bottom, fraction of the view height (0–0.4)
        val embedded: Boolean = true,   // keep colors/fonts written in the subtitle file (ASS, VTT)
    )

    private val _state = MutableStateFlow(Style())
    val state: StateFlow<Style> = _state
    private var ctx: Context? = null

    fun init(context: Context) {
        ctx = context.applicationContext
        val p = context.getSharedPreferences("sub_style", Context.MODE_PRIVATE)
        _state.value = Style(
            p.getFloat("scale", 1f), p.getInt("color", Color.WHITE), p.getBoolean("bold", false),
            Edge.entries.getOrElse(p.getInt("edge", 1)) { Edge.Outline },
            Background.entries.getOrElse(p.getInt("bg", 0)) { Background.None },
            p.getFloat("bottom", 0.08f), p.getBoolean("embedded", true),
        )
    }

    fun set(s: Style) {
        _state.value = s
        ctx?.getSharedPreferences("sub_style", Context.MODE_PRIVATE)?.edit()
            ?.putFloat("scale", s.scale)?.putInt("color", s.color)?.putBoolean("bold", s.bold)
            ?.putInt("edge", s.edge.ordinal)?.putInt("bg", s.background.ordinal)
            ?.putFloat("bottom", s.bottom)?.putBoolean("embedded", s.embedded)?.apply()
    }

    @OptIn(UnstableApi::class)
    fun apply(view: SubtitleView?, s: Style = state.value) {
        view ?: return
        val dark = Color.red(s.color) + Color.green(s.color) + Color.blue(s.color) < 300
        val edgeColor = if (dark) Color.WHITE else Color.BLACK
        val bg = if (s.background == Background.Box) (if (dark) 0xAAFFFFFF.toInt() else 0xAA000000.toInt()) else Color.TRANSPARENT
        val edge = when (s.edge) {
            Edge.None -> CaptionStyleCompat.EDGE_TYPE_NONE
            Edge.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            Edge.Shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
        }
        view.setStyle(CaptionStyleCompat(s.color, bg, Color.TRANSPARENT, edge, edgeColor,
            if (s.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT))
        view.setApplyEmbeddedStyles(s.embedded)
        view.setApplyEmbeddedFontSizes(false)
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.scale)
        view.setBottomPaddingFraction(s.bottom)
    }
}
