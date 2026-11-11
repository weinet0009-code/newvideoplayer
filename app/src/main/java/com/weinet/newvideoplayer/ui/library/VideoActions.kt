package com.weinet.newvideoplayer.ui.library

import com.weinet.newvideoplayer.tr
import com.weinet.newvideoplayer.R

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.formatDuration
import com.weinet.newvideoplayer.data.formatSize

data class VideoAction(val label: String, val icon: ImageVector, val run: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoActionSheet(video: Video, actions: List<VideoAction>, onDismiss: () -> Unit) = ActionSheet(video.title, actions, onDismiss)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String, actions: List<VideoAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        actions.forEach { a ->
            ListItem(
                leadingContent = { Icon(a.icon, null) },
                headlineContent = { Text(a.label) },
                modifier = Modifier.clickable { onDismiss(); a.run() },
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun RenameDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current.substringBeforeLast('.')) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s045)) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !name.contains('/'), onClick = { onRename(name.trim()); onDismiss() }) { Text(tr(R.string.s104)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s056)) } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s056)) } },
    )
}

@Composable
fun DetailsDialog(context: Context, video: Video, onDismiss: () -> Unit) {
    val rows = buildList {
        add(tr(R.string.s061) to video.title)
        if (video.vaultId == null) add(tr(R.string.s105) to video.path.ifEmpty { video.folder })
        add(tr(R.string.s106) to formatDuration(video.durationMs))
        add(tr(R.string.s107) to formatSize(video.sizeBytes))
        if (video.width > 0) add(tr(R.string.s108) to "${video.width} × ${video.height}")
        add(tr(R.string.s109) to video.mimeType)
        add(tr(R.string.s110) to DateFormat.getMediumDateFormat(context).format(video.dateAdded * 1000))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s046)) },
        text = {
            Column {
                rows.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 4.dp)) {
                        Text(k, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(72.dp))
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s002)) } },
    )
}

fun shareVideo(context: Context, video: Video) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = video.mimeType
        putExtra(Intent.EXTRA_STREAM, video.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, tr(R.string.s111)))
}

@Composable
fun BusyDialog(label: String, progress: Float) {
    AlertDialog(
        onDismissRequest = {},
        confirmButton = {},
        title = { Text(label) },
        text = {
            Column {
                LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("${(progress * 100).toInt().coerceIn(0, 100)}%")
            }
        },
    )
}

/** Asks how to save a video's sound: real MP3 (re-encoded) or the original track copied as is. */
@Composable
fun AudioFormatDialog(onDismiss: () -> Unit, onPick: (mp3: Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s043)) },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text(tr(R.string.s112)) },
                    supportingContent = { Text(tr(R.string.s113)) },
                    modifier = Modifier.clickable { onDismiss(); onPick(true) },
                )
                ListItem(
                    headlineContent = { Text(tr(R.string.s114)) },
                    supportingContent = { Text(tr(R.string.s115)) },
                    modifier = Modifier.clickable { onDismiss(); onPick(false) },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s056)) } },
    )
}

fun audioErrorText(e: Exception): String = when (e) {
    is com.weinet.newvideoplayer.tools.AudioExtractor.Unsupported -> tr(R.string.s116, e.mime)
    is com.weinet.newvideoplayer.tools.AudioExtractor.NoAudio -> tr(R.string.s117)
    else -> tr(R.string.s118)
}
