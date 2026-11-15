package com.weinet.newvideoplayer.ui.library

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.weinet.newvideoplayer.R
import com.weinet.newvideoplayer.data.OnlineVideo
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.tr

/** "Open URL": paste or receive a direct video link, then play it or download it. */
@Composable
fun OpenUrlDialog(initial: String?, onDismiss: () -> Unit, onPlay: (Video) -> Unit) {
    val context = LocalContext.current
    val history by OnlineVideo.history.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(initial ?: clipboardUrl(context).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun check(): String? {
        val u = url.trim()
        error = when {
            !OnlineVideo.isValid(u) -> tr(R.string.url_invalid)
            OnlineVideo.isUnsupportedSite(u) -> tr(R.string.url_unsupported_site)
            else -> null
        }
        return u.takeIf { error == null }
    }

    fun play(u: String) {
        val v = OnlineVideo.toVideo(u)
        OnlineVideo.remember(u, v.title)
        onDismiss()
        onPlay(v)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Link, null) },
        title = { Text(tr(R.string.url_open)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    placeholder = { Text("https://…") },
                    supportingText = { Text(error ?: tr(R.string.url_hint)) },
                    isError = error != null,
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { clipboardText(context)?.let { url = OnlineVideo.extractUrl(it) ?: it; error = null } }) {
                            Icon(Icons.Default.ContentPaste, tr(R.string.url_paste))
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(tr(R.string.url_network_note), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (history.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(tr(R.string.url_recent), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { OnlineVideo.clear() }) { Text(tr(R.string.url_clear)) }
                    }
                    LazyColumn(Modifier.heightIn(max = 220.dp)) {
                        items(history, key = { it.url }) { e ->
                            Row(
                                Modifier.fillMaxWidth().clickable { play(e.url) }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(e.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(e.url, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(onClick = { OnlineVideo.forget(e.url) }) { Icon(Icons.Default.Close, tr(R.string.url_clear)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { check()?.let { play(it) } }) { Text(tr(R.string.url_play)) }
        },
        dismissButton = {
            TextButton(onClick = {
                val u = check() ?: return@TextButton
                if (OnlineVideo.isStream(u)) {
                    error = tr(R.string.url_stream_no_download); return@TextButton
                }
                val msg = runCatching { tr(R.string.url_download_started, OnlineVideo.download(context, u)) }
                    .getOrElse { tr(R.string.url_download_failed, it.message ?: "") }
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                OnlineVideo.remember(u, OnlineVideo.titleOf(u))
                onDismiss()
            }) { Text(tr(R.string.url_download)) }
        },
    )
}

private fun clipboardText(context: Context): String? =
    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
        .primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

private fun clipboardUrl(context: Context): String? =
    runCatching { OnlineVideo.extractUrl(clipboardText(context)) }.getOrNull()
