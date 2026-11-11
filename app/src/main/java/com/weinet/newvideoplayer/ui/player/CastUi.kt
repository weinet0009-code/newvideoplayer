package com.weinet.newvideoplayer.ui.player

import com.weinet.newvideoplayer.tr
import com.weinet.newvideoplayer.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.mediarouter.media.MediaRouter
import com.weinet.newvideoplayer.cast.CastManager
import com.weinet.newvideoplayer.data.formatDuration
import kotlinx.coroutines.delay

/** Lists Chromecast devices on the same Wi-Fi and connects to the one picked. */
@Composable
fun CastRouteDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val ok = remember { CastManager.init(context) }
    if (!ok) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s002)) } },
            title = { Text(tr(R.string.s119)) },
            text = { Text(tr(R.string.s120)) },
        )
        return
    }
    val router = remember { MediaRouter.getInstance(context) }
    val selector = remember { CastManager.selector }
    var routes by remember { mutableStateOf(listOf<MediaRouter.RouteInfo>()) }
    DisposableEffect(Unit) {
        fun update() { routes = router.routes.filter { !it.isDefault && it.isEnabled && it.matchesSelector(selector) } }
        val cb = object : MediaRouter.Callback() {
            override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = update()
            override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = update()
            override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = update()
        }
        router.addCallback(selector, cb, MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN)
        update()
        onDispose { router.removeCallback(cb) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s056)) } },
        title = { Text(tr(R.string.s121)) },
        text = {
            if (routes.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.width(12.dp))
                Text(tr(R.string.s122))
            } else LazyColumn {
                items(routes, key = { it.id }) { r ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Tv, null) },
                        headlineContent = { Text(r.name) },
                        supportingContent = { r.description?.let { Text(it) } },
                        modifier = Modifier.clickable { router.selectRoute(r); onDismiss() },
                    )
                }
            }
        },
    )
}

/** Remote control shown on the player screen while a Chromecast is playing. */
@Composable
fun CastPanel(modifier: Modifier = Modifier) {
    val cp = CastManager.castPlayer ?: return
    val name by CastManager.deviceName.collectAsStateWithLifecycle()
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(cp) {
        while (true) {
            if (!dragging) pos = cp.currentPosition
            dur = cp.duration.coerceAtLeast(0)
            playing = cp.isPlaying
            title = cp.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
            delay(500)
        }
    }
    Column(
        modifier.background(Color.Black).pointerInput(Unit) { detectTapGestures { } }.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.CastConnected, null, tint = Color.White, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text(tr(R.string.s124, name ?: tr(R.string.s123)), color = Color.White, style = MaterialTheme.typography.titleMedium)
        Text(title, color = Color.LightGray, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(formatDuration(pos), color = Color.White, style = MaterialTheme.typography.labelMedium)
            Slider(
                value = if (dur > 0) pos.toFloat() / dur else 0f,
                onValueChange = { dragging = true; pos = (it * dur).toLong() },
                onValueChangeFinished = { cp.seekTo(pos); dragging = false },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            Text(formatDuration(dur), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { cp.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, tr(R.string.s125), tint = Color.White) }
            IconButton(onClick = { cp.seekTo((cp.currentPosition - 10_000).coerceAtLeast(0)) }) { Icon(Icons.Default.Replay10, tr(R.string.s126), tint = Color.White) }
            IconButton(onClick = { cp.playWhenReady = !cp.playWhenReady }, modifier = Modifier.size(64.dp)) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) tr(R.string.s092) else tr(R.string.s093),
                    tint = Color.White, modifier = Modifier.size(48.dp))
            }
            IconButton(onClick = { cp.seekTo(cp.currentPosition + 10_000) }) { Icon(Icons.Default.Forward10, tr(R.string.s127), tint = Color.White) }
            IconButton(onClick = { cp.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, tr(R.string.s128), tint = Color.White) }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { CastManager.endSession() }) { Text(tr(R.string.s129), color = Color.White) }
    }
}
