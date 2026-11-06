package com.weinet.newvideoplayer.ui.library

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
fun VideoActionSheet(video: Video, actions: List<VideoAction>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
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
        title = { Text("重新命名") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !name.contains('/'), onClick = { onRename(name.trim()); onDismiss() }) { Text("確定") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun DetailsDialog(context: Context, video: Video, onDismiss: () -> Unit) {
    val rows = buildList {
        add("名稱" to video.title)
        if (video.vaultId == null) add("位置" to video.path.ifEmpty { video.folder })
        add("長度" to formatDuration(video.durationMs))
        add("大小" to formatSize(video.sizeBytes))
        if (video.width > 0) add("解析度" to "${video.width} × ${video.height}")
        add("格式" to video.mimeType)
        add("加入日期" to DateFormat.getMediumDateFormat(context).format(video.dateAdded * 1000))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("詳細資訊") },
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
    )
}

fun shareVideo(context: Context, video: Video) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = video.mimeType
        putExtra(Intent.EXTRA_STREAM, video.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "分享影片"))
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
