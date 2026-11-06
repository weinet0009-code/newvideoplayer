package com.weinet.newvideoplayer.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.weinet.newvideoplayer.data.PlaybackPositionStore
import com.weinet.newvideoplayer.data.PlayerSettings
import com.weinet.newvideoplayer.data.formatDuration
import kotlinx.coroutines.delay
import kotlin.math.abs

private enum class DragMode { None, Seek, Brightness, Volume }

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f, 6f, 8f)

@OptIn(UnstableApi::class)
private enum class Aspect(val label: String, val mode: Int) {
    Fit("適應", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Fill("拉伸", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Zoom("裁切", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    Width("寬度", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
}

private enum class Rotation(val label: String, val value: Int) {
    Auto("自動旋轉", ActivityInfo.SCREEN_ORIENTATION_SENSOR),
    Landscape("橫向", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    Portrait("直向", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
}

private fun subtitleMime(name: String): String = when (name.substringAfterLast('.').lowercase()) {
    "vtt" -> MimeTypes.TEXT_VTT
    "ass", "ssa" -> MimeTypes.TEXT_SSA
    "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
    else -> MimeTypes.APPLICATION_SUBRIP
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(videoId: Long, uri: Uri, title: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val positions = remember { PlaybackPositionStore(context) }
    val settings = remember { PlayerSettings(context) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    var softwareDecoder by remember { mutableStateOf(settings.preferSoftwareDecoder) }
    var subtitleUri by remember { mutableStateOf<Uri?>(null) }
    var subtitleName by remember { mutableStateOf("") }

    fun mediaItem(): MediaItem {
        val b = MediaItem.Builder().setUri(uri)
        subtitleUri?.let {
            b.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(it)
                    .setMimeType(subtitleMime(subtitleName))
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            ))
        }
        return b.build()
    }

    // The player is rebuilt when the decoder preference changes.
    val player = remember(softwareDecoder) {
        buildPlayer(context, softwareDecoder).apply {
            setMediaItem(mediaItem())
            seekTo(positions.get(videoId))
            setPlaybackSpeed(settings.speed)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose {
            positions.save(videoId, player.currentPosition, player.duration)
            player.release()
        }
    }

    var isPlaying by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(Aspect.Fit) }
    var rotation by remember { mutableStateOf(Rotation.Auto) }
    var speed by remember { mutableFloatStateOf(settings.speed) }
    var showSpeed by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            val pos = player.currentPosition
            subtitleUri = picked
            subtitleName = picked.lastPathSegment ?: "sub.srt"
            player.setMediaItem(mediaItem(), pos)
            player.prepare()
            hint = "已載入字幕"
        }
    }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // Fullscreen, keep screen on, restore on exit.
    DisposableEffect(Unit) {
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.hide(WindowInsetsCompat.Type.systemBars())
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
        }
    }
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            if (++tick % 10 == 0) positions.save(videoId, position, player.duration)
            if (controlsVisible && isPlaying && System.currentTimeMillis() - lastTouch > 4_000) controlsVisible = false
            delay(500)
        }
    }
    LaunchedEffect(hint) { if (hint != null) { delay(900); hint = null } }
    LaunchedEffect(rotation) { activity.requestedOrientation = rotation.value }
    BackHandler(enabled = locked) { hint = "螢幕已鎖定" }

    fun touch() { lastTouch = System.currentTimeMillis() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false } },
            update = { it.player = player; it.resizeMode = aspect.mode },
            modifier = Modifier.fillMaxSize(),
        )
        if (night) Box(Modifier.fillMaxSize().background(Color(0x66201000)))

        // Gesture layer.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(locked) {
                    detectTapGestures(
                        onTap = { touch(); controlsVisible = !controlsVisible },
                        onDoubleTap = { o ->
                            if (locked) return@detectTapGestures
                            val third = size.width / 3
                            when {
                                o.x < third -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); hint = "-10 秒" }
                                o.x > third * 2 -> { player.seekTo(player.currentPosition + 10_000); hint = "+10 秒" }
                                else -> player.playWhenReady = !player.playWhenReady
                            }
                        },
                    )
                }
                .pointerInput(locked) {
                    if (locked) return@pointerInput
                    var mode = DragMode.None
                    var startX = 0f
                    var accX = 0f
                    var accY = 0f
                    var startPos = 0L
                    fun seekTarget() = (startPos + (accX / size.width * 120_000).toLong())
                        .coerceIn(0, player.duration.coerceAtLeast(0))
                    detectDragGestures(
                        onDragStart = { o -> mode = DragMode.None; startX = o.x; accX = 0f; accY = 0f; startPos = player.currentPosition },
                        onDragEnd = { if (mode == DragMode.Seek) player.seekTo(seekTarget()); mode = DragMode.None },
                    ) { change, drag ->
                        change.consume()
                        accX += drag.x; accY += drag.y
                        if (mode == DragMode.None && (abs(accX) > 20 || abs(accY) > 20)) {
                            mode = if (abs(accX) > abs(accY)) DragMode.Seek
                            else if (startX < size.width / 2) DragMode.Brightness else DragMode.Volume
                        }
                        when (mode) {
                            DragMode.Seek -> hint = "${formatDuration(seekTarget())} / ${formatDuration(player.duration.coerceAtLeast(0))}"
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
        )

        hint?.let {
            Text(it, color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), MaterialTheme.shapes.small).padding(12.dp))
        }

        // Locked: only the unlock button shows.
        if (locked) {
            AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterStart)) {
                IconButton(onClick = { touch(); locked = false }, modifier = Modifier.padding(24.dp).background(Color(0x99000000), MaterialTheme.shapes.extraLarge)) {
                    Icon(Icons.Default.Lock, contentDescription = "解鎖", tint = Color.White)
                }
            }
        } else {
            AnimatedVisibility(controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    // Top bar
                    Row(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color(0x88000000))
                            .statusBarsPadding().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) }
                        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        TextButton(onClick = { touch(); showSpeed = true }) { Text("${speed}x", color = Color.White) }
                        Ctl(Icons.Default.Subtitles, "字幕") {
                            touch()
                            TrackSelectionDialogBuilder(context, "字幕", player, C.TRACK_TYPE_TEXT).build().show()
                        }
                        Ctl(Icons.Default.Audiotrack, "音軌") {
                            touch()
                            TrackSelectionDialogBuilder(context, "音軌", player, C.TRACK_TYPE_AUDIO).build().show()
                        }
                        Ctl(Icons.Default.MoreVert, "更多") { touch(); showMore = true }
                    }
                    // Center
                    IconButton(
                        onClick = { touch(); player.playWhenReady = !player.playWhenReady },
                        modifier = Modifier.align(Alignment.Center).size(72.dp).background(Color(0x66000000), MaterialTheme.shapes.extraLarge),
                    ) {
                        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "暫停" else "播放", tint = Color.White, modifier = Modifier.size(48.dp))
                    }
                    // Bottom bar
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(Color(0x88000000))
                        .navigationBarsPadding().padding(horizontal = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(formatDuration(position), color = Color.White, style = MaterialTheme.typography.labelMedium)
                            Slider(
                                value = if (duration > 0) position.toFloat() / duration else 0f,
                                onValueChange = { touch(); position = (it * duration).toLong() },
                                onValueChangeFinished = { player.seekTo(position) },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            )
                            Text(formatDuration(duration), color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Ctl(Icons.Default.Lock, "鎖定螢幕") { touch(); locked = true }
                            Ctl(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, "靜音") {
                                touch(); muted = !muted; player.volume = if (muted) 0f else 1f
                                hint = if (muted) "已靜音" else "取消靜音"
                            }
                            Ctl(Icons.Default.AspectRatio, "畫面比例") {
                                touch(); aspect = Aspect.entries[(aspect.ordinal + 1) % Aspect.entries.size]; hint = aspect.label
                            }
                            Ctl(Icons.Default.ScreenRotation, "旋轉") {
                                touch(); rotation = Rotation.entries[(rotation.ordinal + 1) % Rotation.entries.size]; hint = rotation.label
                            }
                            Ctl(if (night) Icons.Default.DarkMode else Icons.Default.LightMode, "夜間模式") {
                                touch(); night = !night; hint = if (night) "夜間模式開" else "夜間模式關"
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("關閉") } },
            title = { Text("播放速度") },
            text = {
                LazyColumn {
                    items(SPEEDS) { s ->
                        Row(Modifier.fillMaxWidth().clickable {
                            speed = s; settings.speed = s; player.setPlaybackSpeed(s); showSpeed = false
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = s == speed, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text("${s}x")
                        }
                    }
                }
            },
        )
    }

    if (showMore) {
        AlertDialog(
            onDismissRequest = { showMore = false },
            confirmButton = { TextButton(onClick = { showMore = false }) { Text("關閉") } },
            title = { Text("更多") },
            text = {
                Column {
                    ListItem(
                        headlineContent = { Text("載入外掛字幕") },
                        supportingContent = { Text("SRT、ASS/SSA、VTT、TTML") },
                        modifier = Modifier.clickable {
                            showMore = false
                            pickSubtitle.launch(arrayOf("application/x-subrip", "text/*", "application/octet-stream", "*/*"))
                        },
                    )
                    ListItem(
                        headlineContent = { Text("解碼方式") },
                        supportingContent = { Text(if (softwareDecoder) "軟體解碼（相容性較高）" else "硬體加速（較省電）") },
                        trailingContent = {
                            Switch(checked = !softwareDecoder, onCheckedChange = { hw ->
                                positions.save(videoId, player.currentPosition, player.duration)
                                settings.preferSoftwareDecoder = !hw
                                softwareDecoder = !hw
                            })
                        },
                    )
                }
            },
        )
    }
}

@Composable
private fun Ctl(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }
}
