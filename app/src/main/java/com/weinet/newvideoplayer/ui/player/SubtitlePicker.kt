package com.weinet.newvideoplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

/** Picks the track at [group] for its type, or turns the type off when [group] is null. */
fun selectTrack(player: Player, type: Int, group: Tracks.Group?) {
    val b = player.trackSelectionParameters.buildUpon()
    if (group == null) {
        b.setTrackTypeDisabled(type, true)
    } else {
        b.setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
    }
    player.trackSelectionParameters = b.build()
}

fun selectTextTrack(player: Player, group: Tracks.Group?) = selectTrack(player, C.TRACK_TYPE_TEXT, group)

private fun trackLabel(f: Format, index: Int, fallback: String): String {
    val lang = f.language?.takeIf { it.isNotBlank() && it != "und" }
        ?.let { Locale.forLanguageTag(it).getDisplayName(Locale.TRADITIONAL_CHINESE) }
    return when {
        !f.label.isNullOrBlank() && lang != null -> "${f.label}（$lang）"
        !f.label.isNullOrBlank() -> f.label!!
        lang != null -> lang
        else -> "$fallback ${index + 1}"
    }
}

@Composable
fun SubtitlePickerDialog(
    player: Player, onLoadFile: () -> Unit, onSelected: (formatId: String?) -> Unit = {},
    /** Label and beside-the-video file name of a saved (deletable) subtitle; null for embedded tracks. */
    deletable: (formatId: String?) -> Pair<String, String?>? = { null },
    onDelete: (formatId: String?) -> Unit = {},
    onDismiss: () -> Unit,
) {
    var confirm by remember { mutableStateOf<Pair<String?, Pair<String, String?>>?>(null) }
    val groups = remember {
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 && it.isSupported }
    }
    val off = remember {
        C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes || groups.none { it.isSelected }
    }

    @Composable
    fun Option(label: String, selected: Boolean, onTrash: (() -> Unit)? = null, onClick: () -> Unit) {
        Row(
            Modifier.fillMaxWidth().clickable { onClick(); onDismiss() }.padding(vertical = if (onTrash == null) 10.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null)
            Spacer(Modifier.width(12.dp))
            Text(label, Modifier.weight(1f))
            if (onTrash != null) IconButton(onClick = onTrash) { Icon(Icons.Default.Delete, "刪除字幕") }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字幕") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Option("關閉字幕", off) { selectTextTrack(player, null); onSelected(null) }
                groups.forEachIndexed { i, g ->
                    val fid = g.getTrackFormat(0).id
                    val info = remember(fid) { deletable(fid) }
                    Option(trackLabel(g.getTrackFormat(0), i, "字幕"), !off && g.isSelected,
                        onTrash = info?.let { { confirm = fid to it } }) { selectTextTrack(player, g); onSelected(fid) }
                }
                if (groups.isEmpty()) {
                    Text("這部影片沒有字幕。可以載入字幕檔，或從「更多」線上搜尋、AI 產生。",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onLoadFile) { Text("載入字幕檔") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
    confirm?.let { (fid, info) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("刪除字幕？") },
            text = {
                Text(if (info.second != null) "「${info.first}」會從播放器移除，影片資料夾裡的 ${info.second} 也會一起刪除。"
                else "「${info.first}」會從播放器移除。你原本載入的字幕檔不會被刪除。")
            },
            confirmButton = { TextButton(onClick = { confirm = null; onDelete(fid); onDismiss() }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}

/** Audio track picker (built in Compose: Media3's TrackSelectionDialogBuilder crashes without an AppCompat theme). */
@Composable
fun AudioTrackPickerDialog(player: Player, onDismiss: () -> Unit) {
    val groups = remember {
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 && it.isSupported }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("音軌") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                groups.forEachIndexed { i, g ->
                    val f = g.getTrackFormat(0)
                    val ch = when (f.channelCount) { 1 -> "單聲道"; 2 -> "立體聲"; 6 -> "5.1"; 8 -> "7.1"; else -> null }
                    val label = listOfNotNull(trackLabel(f, i, "音軌"), ch).joinToString(" · ")
                    Row(
                        Modifier.fillMaxWidth().clickable { selectTrack(player, C.TRACK_TYPE_AUDIO, g); onDismiss() }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = g.isSelected, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
                if (groups.isEmpty()) Text("這部影片沒有可切換的音軌。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}
