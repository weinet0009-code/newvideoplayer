package com.weinet.newvideoplayer.ui.player

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Rational
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import com.weinet.newvideoplayer.data.PlayerSettings
import com.weinet.newvideoplayer.data.formatDuration
import com.weinet.newvideoplayer.playback.PipState
import com.weinet.newvideoplayer.playback.PlaybackService
import com.weinet.newvideoplayer.playback.PlayerHolder
import com.weinet.newvideoplayer.cast.CastManager
import com.weinet.newvideoplayer.tools.AudioExtractor
import com.weinet.newvideoplayer.ui.library.Busy
import com.weinet.newvideoplayer.ui.library.BusyDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class DragMode { None, Seek, Brightness, Volume }

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f, 6f, 8f)

private const val ACTION_PIP_TOGGLE = "com.weinet.newvideoplayer.PIP_TOGGLE"
private const val ACTION_PIP_NEXT = "com.weinet.newvideoplayer.PIP_NEXT"
private const val ACTION_PIP_PREV = "com.weinet.newvideoplayer.PIP_PREV"

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

private fun pipParams(context: Context, player: ExoPlayer, playing: Boolean, autoEnter: Boolean): PictureInPictureParams {
    val size = player.videoSize
    val ratio = if (size.width > 0 && size.height > 0) {
        val r = size.width.toFloat() / size.height
        when {
            r > 2.39f -> Rational(239, 100)
            r < 1 / 2.39f -> Rational(100, 239)
            else -> Rational(size.width, size.height)
        }
    } else Rational(16, 9)

    fun action(icon: Int, title: String, act: String, code: Int) = RemoteAction(
        Icon.createWithResource(context, icon), title, title,
        PendingIntent.getBroadcast(context, code, Intent(act).setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE),
    )
    val actions = buildList {
        if (player.hasPreviousMediaItem()) add(action(android.R.drawable.ic_media_previous, "上一部", ACTION_PIP_PREV, 3))
        add(if (playing) action(android.R.drawable.ic_media_pause, "暫停", ACTION_PIP_TOGGLE, 1)
            else action(android.R.drawable.ic_media_play, "播放", ACTION_PIP_TOGGLE, 1))
        if (player.hasNextMediaItem()) add(action(android.R.drawable.ic_media_next, "下一部", ACTION_PIP_NEXT, 2))
    }
    val b = PictureInPictureParams.Builder().setAspectRatio(ratio).setActions(actions)
    if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(true)
    return b.build()
}

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as Activity
    val playerOrNull by PlayerHolder.player.collectAsStateWithLifecycle()
    val player = playerOrNull
    if (player == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val settings = remember { PlayerSettings(context) }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    val inPip by PipState.inPip

    var softwareDecoder by remember { mutableStateOf(settings.preferSoftwareDecoder) }
    var backgroundPlay by remember { mutableStateOf(settings.backgroundPlay) }
    var autoPip by remember { mutableStateOf(settings.autoPip) }

    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var title by remember { mutableStateOf("") }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrev by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(player.volume == 0f) }
    var night by remember { mutableStateOf(false) }
    var aspect by remember { mutableStateOf(Aspect.Fit) }
    var rotation by remember { mutableStateOf(Rotation.Auto) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var showSpeed by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var showCast by remember { mutableStateOf(false) }
    var showOnlineSubs by remember { mutableStateOf(false) }
    var showAi by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<Busy?>(null) }
    val castActive by CastManager.active.collectAsStateWithLifecycle()
    val castError by CastManager.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun touch() { lastTouch = System.currentTimeMillis() }

    fun exit() {
        PlayerHolder.stop(context)
        onBack()
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT >= 26) activity.enterPictureInPictureMode(pipParams(context, player, isPlaying, autoPip))
    }

    fun skip(next: Boolean) {
        PlayerHolder.saveCurrentPosition(context)
        if (next) player.seekToNextMediaItem() else player.seekToPreviousMediaItem()
    }

    fun currentVideo() = PlayerHolder.queue.getOrNull(player.currentMediaItemIndex)

    fun loadSubtitle(picked: Uri, name: String) {
        val current = player.currentMediaItem
        if (current != null) {
            val item = current.buildUpon().setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(picked)
                    .setMimeType(subtitleMime(name))
                    .setLanguage("und")
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                    .build()
            )).build()
            val pos = player.currentPosition
            player.replaceMediaItem(player.currentMediaItemIndex, item)
            player.seekTo(pos)
            hint = "已載入字幕"
        }
    }

    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) loadSubtitle(picked, picked.lastPathSegment ?: "sub.srt")
    }

    fun extractAudio() {
        val v = currentVideo() ?: return
        scope.launch {
            busy = Busy("轉成音訊…", 0f)
            hint = try {
                "已存到 " + AudioExtractor.toMusic(context, v) { p -> busy = Busy("轉成音訊…", p) }
            } catch (e: AudioExtractor.Unsupported) { "此音訊格式（${e.mime}）無法直接轉出" }
            catch (e: AudioExtractor.NoAudio) { "這部影片沒有聲音" }
            catch (e: Exception) { "轉換失敗" }
            busy = null
        }
    }
    LaunchedEffect(castError) { castError?.let { hint = it; CastManager.error.value = null } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // PiP buttons.
    DisposableEffect(player) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    ACTION_PIP_TOGGLE -> player.playWhenReady = !player.playWhenReady
                    ACTION_PIP_NEXT -> skip(true)
                    ACTION_PIP_PREV -> skip(false)
                }
            }
        }
        val filter = IntentFilter().apply { addAction(ACTION_PIP_TOGGLE); addAction(ACTION_PIP_NEXT); addAction(ACTION_PIP_PREV) }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Keep PiP params (aspect ratio, play/pause icon, auto-enter) current.
    LaunchedEffect(player, isPlaying, autoPip, hasNext, hasPrev) {
        if (Build.VERSION.SDK_INT >= 26) activity.setPictureInPictureParams(pipParams(context, player, isPlaying, autoPip && isPlaying))
    }
    DisposableEffect(Unit) {
        PipState.onUserLeave = { if (Build.VERSION.SDK_INT < 31 && autoPip && player.isPlaying && !PlayerHolder.floating) enterPip() }
        onDispose {
            PipState.onUserLeave = null
            if (Build.VERSION.SDK_INT >= 31) activity.setPictureInPictureParams(
                PictureInPictureParams.Builder().setAutoEnterEnabled(false).build()
            )
        }
    }

    // Leaving the screen: pause unless PiP, floating window or background play keeps it going.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                PlayerHolder.saveCurrentPosition(context)
                if (!PlayerHolder.floating && !settings.backgroundPlay) player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
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
            title = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: ""
            hasNext = player.hasNextMediaItem()
            hasPrev = player.hasPreviousMediaItem()
            if (++tick % 10 == 0) PlayerHolder.saveCurrentPosition(context)
            if (controlsVisible && isPlaying && System.currentTimeMillis() - lastTouch > 4_000) controlsVisible = false
            delay(500)
        }
    }
    LaunchedEffect(hint) { if (hint != null) { delay(if (hint!!.length > 12) 2500 else 900); hint = null } }
    LaunchedEffect(rotation) { activity.requestedOrientation = rotation.value }
    BackHandler { if (locked) hint = "螢幕已鎖定" else exit() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { PlayerView(it).apply { useController = false } },
            update = { it.player = player; it.resizeMode = aspect.mode },
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        if (inPip) return@Box
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
                        IconButton(onClick = { exit() }) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) }
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
                        Ctl(Icons.Default.Cast, "投放") { touch(); showCast = true }
                        Ctl(Icons.Default.PictureInPictureAlt, "子母畫面") { touch(); enterPip() }
                        Ctl(Icons.Default.OpenInNew, "浮動視窗") {
                            touch()
                            if (!Settings.canDrawOverlays(context)) {
                                hint = "請允許「顯示在其他應用程式上層」"
                                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")))
                            } else {
                                PlayerHolder.floating = true
                                PlaybackService.showFloat(context)
                                onBack()
                                activity.moveTaskToBack(true)
                            }
                        }
                        Ctl(Icons.Default.MoreVert, "更多") { touch(); showMore = true }
                    }
                    // Center: previous / play-pause / next
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        if (hasPrev) Ctl(Icons.Default.SkipPrevious, "上一部") { touch(); skip(false) }
                        IconButton(
                            onClick = { touch(); player.playWhenReady = !player.playWhenReady },
                            modifier = Modifier.size(72.dp).background(Color(0x66000000), MaterialTheme.shapes.extraLarge),
                        ) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) "暫停" else "播放", tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                        if (hasNext) Ctl(Icons.Default.SkipNext, "下一部") { touch(); skip(true) }
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

    if (castActive && !inPip) CastPanel(Modifier.fillMaxSize())
    if (showCast) CastRouteDialog(onDismiss = { showCast = false })
    if (showOnlineSubs) currentVideo()?.let { v ->
        OnlineSubtitleDialog(v, onLoaded = { loadSubtitle(Uri.fromFile(it), it.name) }, onDismiss = { showOnlineSubs = false })
    }
    if (showAi) currentVideo()?.let { v ->
        AiSubtitleDialog(v, onLoaded = { loadSubtitle(Uri.fromFile(it), it.name) },
            onOpenSettings = { showApi = true }, onDismiss = { showAi = false })
    }
    if (showApi) ApiSettingsDialog(onDismiss = { showApi = false })
    busy?.let { BusyDialog(it.label, it.progress) }

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
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ListItem(
                        headlineContent = { Text("載入外掛字幕") },
                        supportingContent = { Text("SRT、ASS/SSA、VTT、TTML") },
                        modifier = Modifier.clickable {
                            showMore = false
                            pickSubtitle.launch(arrayOf("*/*"))
                        },
                    )
                    ListItem(
                        headlineContent = { Text("線上搜尋字幕") },
                        supportingContent = { Text("OpenSubtitles") },
                        modifier = Modifier.clickable { showMore = false; showOnlineSubs = true },
                    )
                    ListItem(
                        headlineContent = { Text("AI 產生字幕") },
                        supportingContent = { Text("辨識影片語音，自動產生字幕") },
                        modifier = Modifier.clickable { showMore = false; showAi = true },
                    )
                    ListItem(
                        headlineContent = { Text("轉成音訊檔") },
                        supportingContent = { Text("存到 Music/NewVideoPlayer") },
                        modifier = Modifier.clickable { showMore = false; extractAudio() },
                    )
                    ListItem(
                        headlineContent = { Text("投放到電視") },
                        supportingContent = { Text("Chromecast，需在同一個 Wi-Fi") },
                        modifier = Modifier.clickable { showMore = false; showCast = true },
                    )
                    ListItem(
                        headlineContent = { Text("字幕服務設定") },
                        supportingContent = { Text("OpenAI 金鑰、OpenSubtitles 帳號") },
                        modifier = Modifier.clickable { showMore = false; showApi = true },
                    )
                    ListItem(
                        headlineContent = { Text("背景播放") },
                        supportingContent = { Text("離開 app 後繼續播放聲音，可從通知列控制") },
                        trailingContent = {
                            Switch(checked = backgroundPlay, onCheckedChange = { on ->
                                backgroundPlay = on; settings.backgroundPlay = on
                                if (on && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            })
                        },
                    )
                    ListItem(
                        headlineContent = { Text("按主畫面鍵自動子母畫面") },
                        trailingContent = {
                            Switch(checked = autoPip, onCheckedChange = { autoPip = it; settings.autoPip = it })
                        },
                    )
                    ListItem(
                        headlineContent = { Text("解碼方式") },
                        supportingContent = { Text(if (softwareDecoder) "軟體解碼（相容性較高）" else "硬體加速（較省電）") },
                        trailingContent = {
                            Switch(checked = !softwareDecoder, onCheckedChange = { hw ->
                                settings.preferSoftwareDecoder = !hw
                                softwareDecoder = !hw
                                PlayerHolder.rebuild(context)
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
