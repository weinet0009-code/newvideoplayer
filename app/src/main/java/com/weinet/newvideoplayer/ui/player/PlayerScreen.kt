package com.weinet.newvideoplayer.ui.player

import com.weinet.newvideoplayer.tr
import com.weinet.newvideoplayer.R

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
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.weinet.newvideoplayer.data.SubtitleStore
import com.weinet.newvideoplayer.data.SidecarSubs
import com.weinet.newvideoplayer.playback.Enhance
import com.weinet.newvideoplayer.playback.SleepTimer
import com.weinet.newvideoplayer.data.PlayerSettings
import com.weinet.newvideoplayer.data.formatDuration
import com.weinet.newvideoplayer.playback.PipState
import com.weinet.newvideoplayer.playback.PlaybackService
import com.weinet.newvideoplayer.playback.PlayerHolder
import com.weinet.newvideoplayer.cast.CastManager
import com.weinet.newvideoplayer.tools.AudioExtractor
import com.weinet.newvideoplayer.ui.library.Busy
import com.weinet.newvideoplayer.ui.library.BusyDialog
import com.weinet.newvideoplayer.ui.library.AudioFormatDialog
import com.weinet.newvideoplayer.ui.library.audioErrorText
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class DragMode { None, Seek, Brightness, Volume }

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 3f, 4f, 6f, 8f)

private const val ACTION_PIP_TOGGLE = "com.weinet.newvideoplayer.PIP_TOGGLE"
private const val ACTION_PIP_NEXT = "com.weinet.newvideoplayer.PIP_NEXT"
private const val ACTION_PIP_PREV = "com.weinet.newvideoplayer.PIP_PREV"

@OptIn(UnstableApi::class)
private enum class Aspect(val label: String, val mode: Int) {
    Fit(tr(R.string.s130), AspectRatioFrameLayout.RESIZE_MODE_FIT),
    Fill(tr(R.string.s131), AspectRatioFrameLayout.RESIZE_MODE_FILL),
    Zoom(tr(R.string.s132), AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    Width(tr(R.string.s133), AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
}

private enum class Rotation(val label: String, val value: Int) {
    Auto(tr(R.string.s134), ActivityInfo.SCREEN_ORIENTATION_SENSOR),
    Landscape(tr(R.string.s135), ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
    Portrait(tr(R.string.s136), ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
}

private val REPEAT_LABELS = listOf(tr(R.string.s002), tr(R.string.s137), tr(R.string.s138))

private fun sleepLabel(s: SleepTimer.State): String = when (s) {
    SleepTimer.State.Off -> tr(R.string.s002)
    SleepTimer.State.EndOfVideo -> tr(R.string.s139)
    is SleepTimer.State.At -> {
        val min = ((s.epochMs - System.currentTimeMillis()).coerceAtLeast(0) + 59_999) / 60_000
        tr(R.string.s140, min)
    }
}

private fun subtitleMime(name: String): String = SubtitleStore.mime(name)

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
        if (player.hasPreviousMediaItem()) add(action(android.R.drawable.ic_media_previous, tr(R.string.s125), ACTION_PIP_PREV, 3))
        add(if (playing) action(android.R.drawable.ic_media_pause, tr(R.string.s092), ACTION_PIP_TOGGLE, 1)
            else action(android.R.drawable.ic_media_play, tr(R.string.s093), ACTION_PIP_TOGGLE, 1))
        if (player.hasNextMediaItem()) add(action(android.R.drawable.ic_media_next, tr(R.string.s128), ACTION_PIP_NEXT, 2))
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
    var showTranslate by remember { mutableStateOf(false) }
    var showSubPicker by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showApi by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showAudioFormat by remember { mutableStateOf(false) }
    var repeat by remember { mutableIntStateOf(player.repeatMode) }
    var enhance by remember { mutableIntStateOf(settings.enhance) }
    val sleep by SleepTimer.state.collectAsStateWithLifecycle()
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

    fun floatWindow() {
        if (!Settings.canDrawOverlays(context)) {
            hint = tr(R.string.s141)
            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")))
        } else {
            PlayerHolder.floating = true
            PlaybackService.showFloat(context)
            onBack()
            activity.moveTaskToBack(true)
        }
    }

    // Top-bar actions by priority; whatever doesn't fit the screen width moves into 更多 (更多 is always shown).
    val topActions = listOf(
        TopAction("subs", tr(R.string.s142), Icons.Default.Subtitles) { showSubPicker = true },
        TopAction("speed", tr(R.string.s143), null) { showSpeed = true },
        TopAction("audio", tr(R.string.s144), Icons.Default.Audiotrack) { showAudioPicker = true },
        TopAction("cast", tr(R.string.s145), Icons.Default.Cast) { showCast = true },
        TopAction("pip", tr(R.string.s146), Icons.Default.PictureInPictureAlt) { enterPip() },
        TopAction("float", tr(R.string.s147), Icons.Default.OpenInNew) { floatWindow() },
    )
    val screenDp = LocalConfiguration.current.screenWidthDp
    // back 48 + 更多 48 + padding 8 + title at least 80; speed button ~64, icons 48.
    val fitCount = run {
        var left = screenDp - 48 - 48 - 8 - 80
        var n = 0
        for (a in topActions) { val w = if (a.key == "speed") 64 else 48; if (left < w) break; left -= w; n++ }
        n
    }
    val overflowActions = topActions.drop(fitCount)

    /**
     * Adds an external subtitle to the current video (keeping ones loaded earlier) and switches
     * to it. The user can pick any of them, or turn subtitles off, from the subtitle button.
     */
    fun loadSubtitle(picked: Uri, name: String, label: String = name, sidecar: String? = null) {
        val current = player.currentMediaItem ?: return
        val id = "ext${System.currentTimeMillis()}"
        // Save a copy so the subtitle comes back next time this video is opened.
        val uri = runCatching { SubtitleStore.add(context, current.mediaId, picked, name, label, id, sidecar) }.getOrDefault(picked)
        val existing = current.localConfiguration?.subtitleConfigurations.orEmpty()
            .filter { it.uri != picked && it.uri != uri && it.label != label }
            .map { it.buildUpon().setSelectionFlags(0).build() }
        val config = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(subtitleMime(name))
            .setLanguage("und")
            .setLabel(label)
            .setId(id)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val item = current.buildUpon().setSubtitleConfigurations(existing + config).build()
        // replaceMediaItem() keeps the old media source when only subtitles change (Media3
        // treats it as a metadata update), so the new track never appears. Reset the playlist
        // instead so the source is rebuilt with the subtitles merged in.
        val index = player.currentMediaItemIndex
        val pos = player.currentPosition
        val play = player.playWhenReady
        val items = List(player.mediaItemCount) { if (it == index) item else player.getMediaItemAt(it) }
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .build()
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val group = tracks.groups.firstOrNull {
                    it.type == C.TRACK_TYPE_TEXT && it.length > 0 && it.getTrackFormat(0).id?.contains(id) == true
                } ?: return
                selectTextTrack(player, group)
                player.removeListener(this)
            }
        })
        player.setMediaItems(items, index, pos)
        player.prepare()
        player.playWhenReady = play
        hint = tr(R.string.s148, label)
    }

    /** Removes a saved subtitle (and the copy beside the video, if Hark wrote one) and drops its track. */
    fun deleteSubtitle(formatId: String?) {
        val current = player.currentMediaItem ?: return
        val removed = SubtitleStore.remove(context, current.mediaId, formatId) ?: return
        val (id, sidecar) = removed
        val sideOk = sidecar == null || currentVideo()?.let { SidecarSubs.delete(context, it, sidecar) } == true
        val keep = current.localConfiguration?.subtitleConfigurations.orEmpty().filter { it.id?.contains(id) != true }
        val item = current.buildUpon().setSubtitleConfigurations(keep).build()
        val index = player.currentMediaItemIndex
        val pos = player.currentPosition
        val play = player.playWhenReady
        val items = List(player.mediaItemCount) { if (it == index) item else player.getMediaItemAt(it) }
        player.setMediaItems(items, index, pos)
        player.prepare()
        player.playWhenReady = play
        hint = if (sideOk) tr(R.string.s149) else tr(R.string.s150)
    }

    val pickSubtitle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            val name = runCatching {
                context.contentResolver.query(picked, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull() ?: picked.lastPathSegment ?: "sub.srt"
            loadSubtitle(picked, name)
        }
    }

    fun exportSubtitle() {
        val item = player.currentMediaItem ?: return
        val title = currentVideo()?.title ?: item.mediaMetadata.title?.toString() ?: "subtitle"
        val formatId = player.currentTracks.groups
            .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)?.id
        scope.launch {
            hint = try {
                withContext(Dispatchers.IO) { SubtitleStore.export(context, item.mediaId, formatId, title) }
                    ?.let { tr(R.string.s151, it) } ?: tr(R.string.s152)
            } catch (e: Exception) {
                tr(R.string.s153, e.message)
            }
        }
    }

    fun extractAudio(mp3: Boolean) {
        val v = currentVideo() ?: return
        scope.launch {
            val label = if (mp3) tr(R.string.s085) else tr(R.string.s086)
            busy = Busy(label, 0f)
            hint = try {
                tr(R.string.s087) + AudioExtractor.toMusic(context, v, mp3) { p -> busy = Busy(label, p) }
            } catch (e: Exception) { audioErrorText(e) }
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
    BackHandler { if (locked) hint = tr(R.string.s154) else exit() }

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
                                o.x < third -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); hint = tr(R.string.s126) }
                                o.x > third * 2 -> { player.seekTo(player.currentPosition + 10_000); hint = tr(R.string.s127) }
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
                                hint = tr(R.string.s155, (lp.screenBrightness * 100).toInt())
                            }
                            DragMode.Volume -> {
                                val steps = (-accY / (size.height / maxVolume)).toInt()
                                if (steps != 0) {
                                    val v = (audio.getStreamVolume(AudioManager.STREAM_MUSIC) + steps).coerceIn(0, maxVolume)
                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                    accY = 0f
                                    hint = tr(R.string.s156, v * 100 / maxVolume)
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
                    Icon(Icons.Default.Lock, contentDescription = tr(R.string.s157), tint = Color.White)
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
                        IconButton(onClick = { exit() }) { Icon(Icons.Default.ArrowBack, tr(R.string.s030), tint = Color.White) }
                        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        topActions.take(fitCount).forEach { a ->
                            if (a.key == "speed") TextButton(onClick = { touch(); a.run() }) { Text("${speed}x", color = Color.White) }
                            else Ctl(a.icon!!, a.label) { touch(); a.run() }
                        }
                        Ctl(Icons.Default.MoreVert, tr(R.string.s158)) { touch(); showMore = true }
                    }
                    // Center: previous / play-pause / next
                    Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        if (hasPrev) Ctl(Icons.Default.SkipPrevious, tr(R.string.s125)) { touch(); skip(false) }
                        IconButton(
                            onClick = { touch(); player.playWhenReady = !player.playWhenReady },
                            modifier = Modifier.size(72.dp).background(Color(0x66000000), MaterialTheme.shapes.extraLarge),
                        ) {
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) tr(R.string.s092) else tr(R.string.s093), tint = Color.White, modifier = Modifier.size(48.dp))
                        }
                        if (hasNext) Ctl(Icons.Default.SkipNext, tr(R.string.s128)) { touch(); skip(true) }
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
                            Ctl(Icons.Default.Lock, tr(R.string.s159)) { touch(); locked = true }
                            Ctl(if (muted) Icons.Default.VolumeOff else Icons.Default.VolumeUp, tr(R.string.s160)) {
                                touch(); muted = !muted; player.volume = if (muted) 0f else 1f
                                hint = if (muted) tr(R.string.s161) else tr(R.string.s162)
                            }
                            Ctl(Icons.Default.AspectRatio, tr(R.string.s163)) {
                                touch(); aspect = Aspect.entries[(aspect.ordinal + 1) % Aspect.entries.size]; hint = aspect.label
                            }
                            Ctl(Icons.Default.ScreenRotation, tr(R.string.s164)) {
                                touch(); rotation = Rotation.entries[(rotation.ordinal + 1) % Rotation.entries.size]; hint = rotation.label
                            }
                            Ctl(if (night) Icons.Default.DarkMode else Icons.Default.LightMode, tr(R.string.s165)) {
                                touch(); night = !night; hint = if (night) tr(R.string.s166) else tr(R.string.s167)
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
        AiSubtitleDialog(v, onLoaded = { f, label, side -> loadSubtitle(Uri.fromFile(f), f.name, label, side) }, onDismiss = { showAi = false })
    }
    if (showTranslate) currentVideo()?.let { v ->
        val src = remember {
            val item = player.currentMediaItem
            val formatId = player.currentTracks.groups
                .firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.getTrackFormat(0)?.id
            item?.let { SubtitleStore.selectedFile(context, it.mediaId, formatId) }
        }
        TranslateSubtitleDialog(v, src, onLoaded = { f, label, side -> loadSubtitle(Uri.fromFile(f), f.name, label, side) },
            onDismiss = { showTranslate = false })
    }
    if (showSubPicker) SubtitlePickerDialog(
        player,
        onLoadFile = { showSubPicker = false; pickSubtitle.launch(arrayOf("*/*")) },
        onSelected = { formatId ->
            player.currentMediaItem?.mediaId?.let { m ->
                SubtitleStore.setSelected(context, m, SubtitleStore.idForFormat(context, m, formatId))
            }
        },
        deletable = { formatId -> player.currentMediaItem?.mediaId?.let { SubtitleStore.info(context, it, formatId) } },
        onDelete = { deleteSubtitle(it) },
        onDismiss = { showSubPicker = false },
    )
    if (showAudioPicker) AudioTrackPickerDialog(player, onDismiss = { showAudioPicker = false })
    if (showApi) ApiSettingsDialog(onDismiss = { showApi = false })
    busy?.let { BusyDialog(it.label, it.progress) }
    if (showAudioFormat) AudioFormatDialog(onDismiss = { showAudioFormat = false }, onPick = { extractAudio(it) })

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            confirmButton = { TextButton(onClick = { showSpeed = false }) { Text(tr(R.string.s002)) } },
            title = { Text(tr(R.string.s143)) },
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

    if (showSleep) {
        AlertDialog(
            onDismissRequest = { showSleep = false },
            confirmButton = { TextButton(onClick = { showSleep = false }) { Text(tr(R.string.s002)) } },
            title = { Text(tr(R.string.s098)) },
            text = {
                Column {
                    Row(Modifier.fillMaxWidth().clickable { SleepTimer.cancel(); showSleep = false }
                        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = sleep == SleepTimer.State.Off, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(tr(R.string.s002))
                    }
                    SleepTimer.OPTIONS.forEach { m ->
                        Row(Modifier.fillMaxWidth().clickable {
                            SleepTimer.set(player, m); showSleep = false
                            hint = if (m == 0) tr(R.string.s168) else tr(R.string.s169, m)
                        }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = m == 0 && sleep == SleepTimer.State.EndOfVideo, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(if (m == 0) tr(R.string.s170) else tr(R.string.s103, m))
                        }
                    }
                }
            },
        )
    }

    if (showMore) {
        AlertDialog(
            onDismissRequest = { showMore = false },
            confirmButton = { TextButton(onClick = { showMore = false }) { Text(tr(R.string.s002)) } },
            title = { Text(tr(R.string.s158)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    overflowActions.filter { it.key != "cast" }.forEach { a ->
                        ListItem(
                            headlineContent = { Text(a.label) },
                            supportingContent = { if (a.key == "speed") Text("${speed}x") },
                            leadingContent = { Icon(a.icon ?: Icons.Default.Speed, null) },
                            modifier = Modifier.clickable { showMore = false; a.run() },
                        )
                    }
                    if (overflowActions.any { it.key != "cast" }) HorizontalDivider()
                    ListItem(
                        headlineContent = { Text(tr(R.string.s171)) },
                        supportingContent = { Text(REPEAT_LABELS[repeat]) },
                        modifier = Modifier.clickable {
                            repeat = (repeat + 1) % 3
                            player.repeatMode = repeat; settings.repeatMode = repeat
                        },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s098)) },
                        supportingContent = { Text(sleepLabel(sleep)) },
                        modifier = Modifier.clickable { showMore = false; showSleep = true },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s172)) },
                        supportingContent = { Text(Enhance.LABELS[enhance] + tr(R.string.s173)) },
                        modifier = Modifier.clickable {
                            enhance = (enhance + 1) % Enhance.LABELS.size
                            PlayerHolder.setEnhance(context, enhance)
                        },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s174)) },
                        supportingContent = { Text(tr(R.string.s175)) },
                        modifier = Modifier.clickable {
                            showMore = false
                            pickSubtitle.launch(arrayOf("*/*"))
                        },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s176)) },
                        supportingContent = { Text("OpenSubtitles") },
                        modifier = Modifier.clickable { showMore = false; showOnlineSubs = true },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s177)) },
                        supportingContent = { Text(tr(R.string.s178)) },
                        modifier = Modifier.clickable { showMore = false; showAi = true },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s179)) },
                        supportingContent = { Text(tr(R.string.s180)) },
                        modifier = Modifier.clickable { showMore = false; showTranslate = true },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s181)) },
                        supportingContent = { Text(tr(R.string.s182)) },
                        modifier = Modifier.clickable { showMore = false; exportSubtitle() },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s043)) },
                        supportingContent = { Text(tr(R.string.s183)) },
                        modifier = Modifier.clickable { showMore = false; showAudioFormat = true },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s184)) },
                        supportingContent = { Text(tr(R.string.s185)) },
                        modifier = Modifier.clickable { showMore = false; showCast = true },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s186)) },
                        supportingContent = { Text(tr(R.string.s187)) },
                        modifier = Modifier.clickable { showMore = false; showApi = true },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s188)) },
                        supportingContent = { Text(tr(R.string.s189)) },
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
                        headlineContent = { Text(tr(R.string.s190)) },
                        trailingContent = {
                            Switch(checked = autoPip, onCheckedChange = { autoPip = it; settings.autoPip = it })
                        },
                    )
                    ListItem(
                        headlineContent = { Text(tr(R.string.s191)) },
                        supportingContent = { Text(if (softwareDecoder) tr(R.string.s192) else tr(R.string.s193)) },
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

private class TopAction(val key: String, val label: String, val icon: ImageVector?, val run: () -> Unit)

@Composable
private fun Ctl(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = Color.White) }
}
