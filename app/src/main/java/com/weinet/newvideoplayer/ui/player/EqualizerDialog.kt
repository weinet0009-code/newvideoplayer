package com.weinet.newvideoplayer.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.weinet.newvideoplayer.R
import com.weinet.newvideoplayer.playback.AudioEq
import com.weinet.newvideoplayer.tr
import kotlin.math.roundToInt

private val SLIDER_H = 190.dp

/**
 * iTunes-style equalizer: on/off and scene dropdown on top; pre-amp (auto or manual) on the left,
 * a ±12 dB scale, then ten vertical band sliders. Moving a band switches the scene to custom;
 * double-tap a slider resets it to 0 dB.
 */
@Composable
fun EqualizerDialog(onDismiss: () -> Unit) {
    val s by AudioEq.state.collectAsState()
    val on = s.enabled
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.eq_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Switch(checked = on, onCheckedChange = { AudioEq.set(s.copy(enabled = it)) })
                }
                Spacer(Modifier.height(8.dp))
                PresetDropdown(s, on)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = s.autoPreamp, enabled = on, onCheckedChange = { auto ->
                        AudioEq.set(if (auto) s.copy(autoPreamp = true) else s.copy(autoPreamp = false, preamp = s.effectivePreamp))
                    })
                    Text(tr(R.string.eq_preamp) + "：" + tr(R.string.s236), style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth().alpha(if (on) 1f else 0.45f)) {
                    // Pre-amp column.
                    Band(tr(R.string.eq_preamp), s.effectivePreamp, enabled = on && !s.autoPreamp, wide = true) { v ->
                        AudioEq.set(s.copy(autoPreamp = false, preamp = v))
                    }
                    Spacer(Modifier.width(6.dp))
                    Scale()
                    val gains = s.gains
                    AudioEq.BANDS_HZ.forEachIndexed { i, hz ->
                        Box(Modifier.weight(1f)) {
                            Band(if (hz >= 1000) "${hz / 1000}K" else "$hz", gains[i], enabled = on) { v ->
                                val c = gains.copyOf(); c[i] = v
                                AudioEq.set(s.copy(preset = AudioEq.CUSTOM, custom = c))
                            }
                        }
                    }
                }
                Text(tr(R.string.eq_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = on, onClick = {
                        AudioEq.set(s.copy(preset = "flat", custom = FloatArray(10), autoPreamp = true, preamp = 0f))
                    }) { Text(tr(R.string.eq_reset)) }
                    TextButton(onClick = onDismiss) { Text(tr(R.string.s265)) }
                }
            }
        }
    }
}

@Composable
private fun PresetDropdown(s: AudioEq.State, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(enabled = enabled, onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(tr(AudioEq.nameRes(s.preset)), modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            AudioEq.PRESETS.forEach { p ->
                DropdownMenuItem(text = { Text(tr(p.nameRes)) }, onClick = { open = false; AudioEq.set(AudioEq.choosePreset(s, p.key)) })
            }
            DropdownMenuItem(text = { Text(tr(R.string.eq_custom)) }, onClick = { open = false; AudioEq.set(AudioEq.choosePreset(s, AudioEq.CUSTOM)) })
        }
    }
}

/** +12 dB / 0 dB / -12 dB labels lined up with the slider travel. */
@Composable
private fun Scale() {
    Column(Modifier.width(38.dp)) {
        Spacer(Modifier.height(16.dp))
        Box(Modifier.height(SLIDER_H).fillMaxWidth()) {
            val st = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp)
            Text("+12 dB", style = st, modifier = Modifier.align(Alignment.TopEnd).padding(end = 2.dp))
            Text("0 dB", style = st, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 2.dp))
            Text("−12 dB", style = st, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp))
        }
    }
}

/** One vertical slider: value above while dragging, track with a 0 dB tick, label below. */
@Composable
private fun Band(label: String, value: Float, enabled: Boolean, wide: Boolean = false, onChange: (Float) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    val cur by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val en by rememberUpdatedState(enabled)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val tick = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    val max = AudioEq.MAX_DB
    fun valueAt(y: Float, h: Float) = (max - (y / h).coerceIn(0f, 1f) * 2 * max).roundToInt().toFloat()
    Column(if (wide) Modifier.width(48.dp) else Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            (if (value > 0) "+" else "") + value.roundToInt(), fontSize = 9.sp, textAlign = TextAlign.Center,
            modifier = Modifier.height(16.dp).alpha(if (dragging || wide) 1f else 0f),
        )
        Canvas(
            Modifier.height(SLIDER_H).fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { o -> if (en) { dragging = true; change(valueAt(o.y, size.height.toFloat())) } },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { c, _ -> if (en) { val v = valueAt(c.position.y, size.height.toFloat()); if (v != cur) change(v) } }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { if (en) change(0f) },
                        onTap = { o -> if (en) change(valueAt(o.y, size.height.toFloat())) },
                    )
                },
        ) {
            val cx = size.width / 2
            val r = 7.dp.toPx()
            val h = size.height
            drawLine(track, Offset(cx, r), Offset(cx, h - r), strokeWidth = 4.dp.toPx())
            drawLine(tick, Offset(cx - 6.dp.toPx(), h / 2), Offset(cx + 6.dp.toPx(), h / 2), strokeWidth = 1.dp.toPx())
            val y = r + (max - value) / (2 * max) * (h - 2 * r)
            drawCircle(Color.White, r + 1.5.dp.toPx(), Offset(cx, y))
            drawCircle(primary, r - 1.dp.toPx(), Offset(cx, y))
        }
        Text(label, fontSize = 10.sp, maxLines = if (wide) 2 else 1, softWrap = wide, lineHeight = 11.sp, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp))
    }
}

/** "關閉" or the scene's name, for list subtitles. */
fun eqSummary(s: AudioEq.State): String = if (!s.enabled) tr(R.string.s002) else tr(AudioEq.nameRes(s.preset))
