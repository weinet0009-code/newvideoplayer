package com.weinet.newvideoplayer.ui.library

import com.weinet.newvideoplayer.tr
import com.weinet.newvideoplayer.R

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.formatDuration
import com.weinet.newvideoplayer.playback.PlayerHolder
import com.weinet.newvideoplayer.playback.SleepTimer
import kotlinx.coroutines.delay

/** Album cover, or a music note when the song has none. */
@Composable
private fun Cover(model: Any?, size: Dp, corner: Dp = 6.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(corner)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size / 2))
        if (model != null) AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongRow(song: Video, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        leadingContent = { Cover(song.artUri, 48.dp) },
        headlineContent = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(listOf(song.artist.ifBlank { tr(R.string.s073) }, formatDuration(song.durationMs)).joinToString(" · "),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
fun SongList(songs: List<Video>, onOpen: (List<Video>, Int) -> Unit, onLongPress: (Video) -> Unit) {
    LazyColumn {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { if (songs.isNotEmpty()) onOpen(songs, 0) }) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(tr(R.string.s088, songs.size))
                }
                TextButton(onClick = {
                    if (songs.isNotEmpty()) {
                        onOpen(songs, songs.indices.random())
                        PlayerHolder.player.value?.shuffleModeEnabled = true
                    }
                }) { Icon(Icons.Default.Shuffle, null); Spacer(Modifier.width(4.dp)); Text(tr(R.string.s089)) }
            }
        }
        items(songs.size, key = { songs[it].id }) { i ->
            SongRow(songs[i], onClick = { onOpen(songs, i) }, onLongClick = { onLongPress(songs[i]) })
        }
    }
}

@Composable
fun MusicGroupList(groups: Map<String, List<Video>>, sub: MusicSub, onSelect: (String) -> Unit) {
    LazyColumn {
        items(groups.entries.toList(), key = { it.key }) { (name, songs) ->
            ListItem(
                leadingContent = {
                    when (sub) {
                        MusicSub.Albums -> Cover(songs.firstOrNull()?.artUri, 48.dp)
                        MusicSub.Artists -> Icon(Icons.Default.Person, null)
                        else -> Icon(Icons.Default.Folder, null)
                    }
                },
                headlineContent = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val extra = if (sub == MusicSub.Albums) songs.firstOrNull()?.artist?.takeIf { it.isNotBlank() } else null
                    Text(listOfNotNull(extra, tr(R.string.s090, songs.size)).joinToString(" · "))
                },
                modifier = Modifier.clickable { onSelect(name) },
            )
        }
    }
}

/** A playlist's items: songs and videos mixed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaList(items: List<Video>, onOpen: (List<Video>, Int) -> Unit, onLongPress: (Video) -> Unit) {
    val context = LocalContext.current
    LazyColumn {
        items(items.size, key = { items[it].id }) { i ->
            val v = items[i]
            if (v.isAudio) SongRow(v, onClick = { onOpen(items, i) }, onLongClick = { onLongPress(v) })
            else ListItem(
                leadingContent = {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(v.uri).videoFrameMillis(1_000).build(),
                        contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 85.dp, height = 48.dp).clip(RoundedCornerShape(6.dp)),
                    )
                },
                headlineContent = { Text(v.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(tr(R.string.s091) + formatDuration(v.durationMs)) },
                modifier = Modifier.combinedClickable(onClick = { onOpen(items, i) }, onLongClick = { onLongPress(v) }),
            )
        }
    }
}

private data class NowPlaying(
    val title: String, val artist: String, val art: Any?, val playing: Boolean,
    val hasNext: Boolean, val hasPrev: Boolean, val shuffle: Boolean, val repeat: Int,
)

/** Follows the shared player; null when no song is loaded. */
@Composable
private fun rememberNowPlaying(): Pair<Player, NowPlaying>? {
    val player by PlayerHolder.player.collectAsStateWithLifecycle()
    val p = player ?: return null
    fun read(): NowPlaying? {
        val item = p.currentMediaItem ?: return null
        val md = item.mediaMetadata
        if (md.mediaType != MediaMetadata.MEDIA_TYPE_MUSIC) return null
        return NowPlaying(
            md.title?.toString() ?: "", md.artist?.toString().orEmpty(), md.artworkUri,
            p.playWhenReady && p.playbackState != Player.STATE_ENDED,
            p.hasNextMediaItem(), p.hasPreviousMediaItem(), p.shuffleModeEnabled, p.repeatMode,
        )
    }
    var state by remember(p) { mutableStateOf(read()) }
    DisposableEffect(p) {
        val l = object : Player.Listener { override fun onEvents(player: Player, events: Player.Events) { state = read() } }
        p.addListener(l)
        state = read()
        onDispose { p.removeListener(l) }
    }
    return state?.let { p to it }
}

@Composable
fun MiniPlayer(onExpand: () -> Unit) {
    val (player, np) = rememberNowPlaying() ?: return
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onExpand).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Cover(np.art, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(np.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                if (np.artist.isNotBlank()) Text(np.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { if (np.playing) player.pause() else player.play() }) {
                Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (np.playing) tr(R.string.s092) else tr(R.string.s093))
            }
            IconButton(enabled = np.hasNext, onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, tr(R.string.s094)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingSheet(onDismiss: () -> Unit) {
    val now = rememberNowPlaying()
    if (now == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    val (player, np) = now
    val context = LocalContext.current
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showSleep by remember { mutableStateOf(false) }
    val sleep by SleepTimer.state.collectAsStateWithLifecycle()
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0)
            delay(500)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Cover(np.art, 260.dp, 12.dp)
            Spacer(Modifier.height(20.dp))
            Text(np.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(np.artist.ifBlank { tr(R.string.s073) }, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Slider(
                value = dragging ?: if (duration > 0) position.toFloat() / duration else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = { dragging?.let { player.seekTo((it * duration).toLong()) }; dragging = null },
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration(position), style = MaterialTheme.typography.labelSmall)
                Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { player.shuffleModeEnabled = !np.shuffle }) {
                    Icon(Icons.Default.Shuffle, tr(R.string.s095), tint = if (np.shuffle) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
                IconButton(onClick = { player.seekToPreviousMediaItem() }) { Icon(Icons.Default.SkipPrevious, tr(R.string.s096)) }
                FilledIconButton(onClick = { if (np.playing) player.pause() else player.play() }, modifier = Modifier.size(64.dp)) {
                    Icon(if (np.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (np.playing) tr(R.string.s092) else tr(R.string.s093),
                        modifier = Modifier.size(36.dp))
                }
                IconButton(enabled = np.hasNext, onClick = { player.seekToNextMediaItem() }) { Icon(Icons.Default.SkipNext, tr(R.string.s094)) }
                IconButton(onClick = {
                    player.repeatMode = when (np.repeat) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                }) {
                    Icon(if (np.repeat == Player.REPEAT_MODE_ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, tr(R.string.s097),
                        tint = if (np.repeat != Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.primary else LocalContentColor.current)
                }
            }
            Row(horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { showSleep = true }) {
                    Icon(Icons.Default.Bedtime, null); Spacer(Modifier.width(4.dp))
                    Text(if (sleep == SleepTimer.State.Off) tr(R.string.s098) else tr(R.string.s099))
                }
                TextButton(onClick = { SleepTimer.cancel(); PlayerHolder.stop(context); onDismiss() }) {
                    Icon(Icons.Default.Stop, null); Spacer(Modifier.width(4.dp)); Text(tr(R.string.s100))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (showSleep) {
        val exo = PlayerHolder.player.value
        AlertDialog(
            onDismissRequest = { showSleep = false },
            confirmButton = { TextButton(onClick = { showSleep = false }) { Text(tr(R.string.s002)) } },
            title = { Text(tr(R.string.s098)) },
            text = {
                Column {
                    ListItem(headlineContent = { Text(tr(R.string.s101)) }, modifier = Modifier.clickable { SleepTimer.cancel(); showSleep = false })
                    SleepTimer.OPTIONS.forEach { m ->
                        ListItem(
                            headlineContent = { Text(if (m == 0) tr(R.string.s102) else tr(R.string.s103, m)) },
                            modifier = Modifier.clickable { exo?.let { SleepTimer.set(it, m) }; showSleep = false },
                        )
                    }
                }
            },
        )
    }
}
