package com.weinet.newvideoplayer.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.weinet.newvideoplayer.R
import com.weinet.newvideoplayer.playback.AudioEq
import com.weinet.newvideoplayer.tr
import kotlin.math.roundToInt

/** Equalizer: on/off, ten scenes plus custom, and a slider per band (editing switches to custom). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EqualizerDialog(onDismiss: () -> Unit) {
    val s by AudioEq.state.collectAsState()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr(R.string.eq_title), Modifier.weight(1f))
                Switch(checked = s.enabled, onCheckedChange = { AudioEq.set(s.copy(enabled = it)) })
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AudioEq.PRESETS.forEach { p ->
                        FilterChip(selected = s.preset == p.key, enabled = s.enabled,
                            onClick = { AudioEq.set(s.copy(preset = p.key)) }, label = { Text(tr(p.nameRes)) })
                    }
                    FilterChip(selected = s.preset == AudioEq.CUSTOM, enabled = s.enabled,
                        onClick = { AudioEq.set(s.copy(preset = AudioEq.CUSTOM)) }, label = { Text(tr(R.string.eq_custom)) })
                }
                val gains = s.gains
                AudioEq.BANDS_HZ.forEachIndexed { i, hz ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (hz >= 1000) tr(R.string.eq_khz, hz / 1000) else tr(R.string.eq_hz, hz),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(56.dp))
                        Slider(
                            value = gains[i], valueRange = -AudioEq.MAX_DB..AudioEq.MAX_DB, steps = 23,
                            enabled = s.enabled, modifier = Modifier.weight(1f),
                            onValueChange = { v ->
                                val c = gains.copyOf(); c[i] = v.roundToInt().toFloat()
                                AudioEq.set(s.copy(preset = AudioEq.CUSTOM, custom = c))
                            },
                        )
                        Text(tr(R.string.eq_db, (if (gains[i] > 0) "+" else "") + gains[i].roundToInt()),
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(44.dp).padding(start = 4.dp))
                    }
                }
                Text(tr(R.string.eq_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        dismissButton = {
            TextButton(enabled = s.enabled, onClick = { AudioEq.set(s.copy(preset = "flat", custom = FloatArray(10))) }) { Text(tr(R.string.eq_reset)) }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s265)) } },
    )
}

/** "關閉" or the scene's name, for list subtitles. */
fun eqSummary(s: AudioEq.State): String = if (!s.enabled) tr(R.string.s002) else tr(AudioEq.nameRes(s.preset))
