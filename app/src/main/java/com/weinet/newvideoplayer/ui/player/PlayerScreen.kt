package com.weinet.newvideoplayer.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.weinet.newvideoplayer.data.PlaybackPositionStore
import com.weinet.newvideoplayer.data.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class DragMode { None, Seek, Brightness, Volume }

@Composable
fun PlayerScreen(videoId: Long, uri: Uri, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val positions = remember { PlaybackPositionStore(context) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            seekTo(positions.get(videoId))
            prepare()
            playWhenReady = true
        }
    }

    var hint by remember { mutableStateOf<String?>(null) }

    // Fullscreen + keep screen on while playing; save position on exit.
    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            positions.save(videoId, player.currentPosition, player.duration)
            player.release()
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    // Save progress periodically in case the app is killed.
    LaunchedEffect(Unit) {
        while (true) { delay(5_000); positions.save(videoId, player.currentPosition, player.duration) }
    }
    LaunchedEffect(hint) { if (hint != null) { delay(800); hint = null } }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) {
                // Double tap: left third rewinds 10s, right third forwards 10s, middle toggles play.
                detectTapGestures(onDoubleTap = { o ->
                    val third = size.width / 3
                    when {
                        o.x < third -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); hint = "-10 秒" }
                        o.x > third * 2 -> { player.seekTo(player.currentPosition + 10_000); hint = "+10 秒" }
                        else -> player.playWhenReady = !player.playWhenReady
                    }
                })
            }
            .pointerInput(Unit) {
                // Horizontal drag seeks; vertical drag on the left sets brightness, on the right volume.
                var mode = DragMode.None
                var startX = 0f
                var accX = 0f
                var accY = 0f
                var startPos = 0L
                detectDragGestures(
                    onDragStart = { o -> mode = DragMode.None; startX = o.x; accX = 0f; accY = 0f; startPos = player.currentPosition },
                    onDragEnd = {
                        if (mode == DragMode.Seek) player.seekTo(
                            (startPos + (accX / size.width * 120_000).toLong()).coerceIn(0, player.duration.coerceAtLeast(0))
                        )
                        mode = DragMode.None
                    },
                ) { change, drag ->
                    change.consume()
                    accX += drag.x; accY += drag.y
                    if (mode == DragMode.None && (abs(accX) > 20 || abs(accY) > 20)) {
                        mode = if (abs(accX) > abs(accY)) DragMode.Seek
                        else if (startX < size.width / 2) DragMode.Brightness else DragMode.Volume
                    }
                    when (mode) {
                        DragMode.Seek -> {
                            val target = (startPos + (accX / size.width * 120_000).toLong()).coerceAtLeast(0)
                            hint = formatDuration(target)
                        }
                        DragMode.Brightness -> {
                            val lp = activity.window.attributes
                            val cur = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                            lp.screenBrightness = (cur - drag.y / size.height).coerceIn(0.01f, 1f)
                            activity.window.attributes = lp
                            hint = "亮度 ${(lp.screenBrightness * 100).toInt()}%"
                        }
                        DragMode.Volume -> {
                            val steps = (-accY / (size.height / maxVolume)).toInt()
                            if (steps != 0) {
                                val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, maxVolume)
                                audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                accY = 0f
                                hint = "音量 ${v * 100 / maxVolume}%"
                            }
                        }
                        DragMode.None -> Unit
                    }
                }
            }
    ) {
        AndroidView(
            factory = { PlayerView(it).apply { this.player = player; setShowNextButton(false); setShowPreviousButton(false) } },
            modifier = Modifier.fillMaxSize(),
        )
        hint?.let {
            Text(
                it, color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(12.dp),
            )
        }
    }
}
