package com.weinet.newvideoplayer.ui.player

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView
import com.weinet.newvideoplayer.R
import com.weinet.newvideoplayer.playback.SubtitleStyle
import com.weinet.newvideoplayer.tr
import kotlin.math.roundToInt

/** Subtitle look: live preview on top, then size, color, bold, outline, background, position. */
@OptIn(UnstableApi::class)
@Composable
fun SubtitleStyleDialog(onDismiss: () -> Unit) {
    val s by SubtitleStyle.state.collectAsState()
    val preview = tr(R.string.sub_preview)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.sub_style)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(12.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF5B7A99), Color(0xFF2E3B2A))))) {
                    AndroidView(
                        factory = { SubtitleView(it) },
                        update = { v ->
                            SubtitleStyle.apply(v, s)
                            v.setCues(listOf(Cue.Builder().setText(preview).build()))
                        },
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                    )
                }
                Label(tr(R.string.sub_size) + "  ${(s.scale * 100).roundToInt()}%")
                Slider(value = s.scale, valueRange = 0.5f..2f, steps = 14, onValueChange = { SubtitleStyle.set(s.copy(scale = it)) })

                Label(tr(R.string.sub_color))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SubtitleStyle.COLORS.forEach { c ->
                        val sel = c == s.color
                        Box(Modifier.size(32.dp).clip(CircleShape).background(Color(c))
                            .border(if (sel) 3.dp else 1.dp, if (sel) MaterialTheme.colorScheme.primary else Color.Gray, CircleShape)
                            .clickable { SubtitleStyle.set(s.copy(color = c)) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.sub_bold), Modifier.weight(1f))
                    Switch(checked = s.bold, onCheckedChange = { SubtitleStyle.set(s.copy(bold = it)) })
                }

                Label(tr(R.string.sub_edge))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SubtitleStyle.Edge.None to R.string.sub_none, SubtitleStyle.Edge.Outline to R.string.sub_edge_outline,
                        SubtitleStyle.Edge.Shadow to R.string.sub_edge_shadow).forEach { (e, r) ->
                        FilterChip(selected = s.edge == e, onClick = { SubtitleStyle.set(s.copy(edge = e)) }, label = { Text(tr(r)) })
                    }
                }
                Label(tr(R.string.sub_bg))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(SubtitleStyle.Background.None to R.string.sub_none, SubtitleStyle.Background.Box to R.string.sub_bg_box).forEach { (b, r) ->
                        FilterChip(selected = s.background == b, onClick = { SubtitleStyle.set(s.copy(background = b)) }, label = { Text(tr(r)) })
                    }
                }
                Label(tr(R.string.sub_position))
                Slider(value = s.bottom, valueRange = 0f..0.4f, steps = 19, onValueChange = { SubtitleStyle.set(s.copy(bottom = it)) })

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.sub_embedded))
                        Text(tr(R.string.sub_embedded_hint), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = s.embedded, onCheckedChange = { SubtitleStyle.set(s.copy(embedded = it)) })
                }
            }
        },
        dismissButton = { TextButton(onClick = { SubtitleStyle.set(SubtitleStyle.Style()) }) { Text(tr(R.string.eq_reset)) } },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s265)) } },
    )
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
