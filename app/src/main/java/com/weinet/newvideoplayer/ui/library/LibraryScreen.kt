package com.weinet.newvideoplayer.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.weinet.newvideoplayer.data.LibraryStore
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (List<Video>, Int) -> Unit, vm: LibraryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) vm.refresh()
    }
    LaunchedEffect(Unit) { launcher.launch(permission) }
    BackHandler(enabled = state.query.isNotEmpty() || state.selectedFolder != null || state.selectedPlaylist != null) { vm.back() }

    var addTarget by remember { mutableStateOf<Video?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var deletePlaylist by remember { mutableStateOf<String?>(null) }

    val title = state.selectedFolder ?: state.selectedPlaylist ?: "影片"
    val inside = state.selectedFolder != null || state.selectedPlaylist != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (inside) IconButton(onClick = { vm.back() }) { Icon(Icons.Default.ArrowBack, "返回") }
                },
                actions = {
                    if (state.tab == LibraryTab.Recent && state.recent.isNotEmpty())
                        TextButton(onClick = { LibraryStore.clearRecent() }) { Text("清除") }
                    if (state.tab == LibraryTab.Playlists && state.selectedPlaylist == null)
                        IconButton(onClick = { showNewPlaylist = true }) { Icon(Icons.Default.Add, "新增播放清單") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                LibraryTab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = state.tab == t,
                        onClick = { vm.selectTab(t) },
                        icon = {
                            Icon(when (t) {
                                LibraryTab.Folders -> Icons.Default.Folder
                                LibraryTab.Recent -> Icons.Default.History
                                LibraryTab.Playlists -> Icons.Default.QueueMusic
                            }, null)
                        },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !granted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { launcher.launch(permission) }) { Text("允許讀取影片") }
                }
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    if (state.tab == LibraryTab.Folders) {
                        OutlinedTextField(
                            value = state.query, onValueChange = vm::search,
                            placeholder = { Text("搜尋影片") }, singleLine = true,
                            leadingIcon = { Icon(Icons.Default.Search, null) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    when (state.tab) {
                        LibraryTab.Folders -> when {
                            state.query.isNotBlank() -> VideoList(state.searchResults, onOpen, onLongPress = { addTarget = it })
                            state.selectedFolder != null -> VideoList(state.folderVideos, onOpen, onLongPress = { addTarget = it })
                            else -> FolderList(state.folders, onSelect = vm::selectFolder)
                        }
                        LibraryTab.Recent ->
                            if (state.recent.isEmpty()) Empty("還沒有播放紀錄")
                            else VideoList(state.recent, onOpen, onLongPress = { addTarget = it })
                        LibraryTab.Playlists -> {
                            val name = state.selectedPlaylist
                            if (name == null) {
                                if (state.playlists.isEmpty()) Empty("點右上角 + 建立播放清單，長按影片加入")
                                else PlaylistList(state.playlists, onSelect = vm::selectPlaylist, onLongPress = { deletePlaylist = it })
                            } else {
                                val vids = state.playlistVideos(name)
                                if (vids.isEmpty()) Empty("長按任何影片即可加入這個清單")
                                else VideoList(vids, onOpen, onLongPress = { LibraryStore.removeFromPlaylist(name, it.id) },
                                    longPressHint = "長按移出清單")
                            }
                        }
                    }
                }
            }
        }
    }

    addTarget?.let { v ->
        AddToPlaylistDialog(
            video = v,
            playlists = state.playlists.keys.toList(),
            onDismiss = { addTarget = null },
        )
    }
    if (showNewPlaylist) {
        NewPlaylistDialog(onDismiss = { showNewPlaylist = false }, onCreate = { LibraryStore.createPlaylist(it) })
    }
    deletePlaylist?.let { name ->
        AlertDialog(
            onDismissRequest = { deletePlaylist = null },
            title = { Text("刪除「$name」？") },
            text = { Text("只會刪除清單，影片不會被刪除。") },
            confirmButton = { TextButton(onClick = { LibraryStore.deletePlaylist(name); deletePlaylist = null }) { Text("刪除") } },
            dismissButton = { TextButton(onClick = { deletePlaylist = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FolderList(folders: Map<String, List<Video>>, onSelect: (String) -> Unit) {
    LazyColumn {
        items(folders.entries.toList(), key = { it.key }) { (name, videos) ->
            ListItem(
                leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                headlineContent = { Text(name) },
                supportingContent = { Text("${videos.size} 部影片") },
                modifier = Modifier.clickable { onSelect(name) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistList(playlists: Map<String, List<Long>>, onSelect: (String) -> Unit, onLongPress: (String) -> Unit) {
    LazyColumn {
        items(playlists.entries.toList(), key = { it.key }) { (name, ids) ->
            ListItem(
                leadingContent = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                headlineContent = { Text(name) },
                supportingContent = { Text("${ids.size} 部影片") },
                modifier = Modifier.combinedClickable(onClick = { onSelect(name) }, onLongClick = { onLongPress(name) }),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoList(
    videos: List<Video>,
    onOpen: (List<Video>, Int) -> Unit,
    onLongPress: (Video) -> Unit,
    longPressHint: String? = null,
) {
    val context = LocalContext.current
    LazyColumn {
        if (longPressHint != null) item {
            Text(longPressHint, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        items(videos.size, key = { videos[it].id }) { i ->
            val v = videos[i]
            Row(
                Modifier.fillMaxWidth()
                    .combinedClickable(onClick = { onOpen(videos, i) }, onLongClick = { onLongPress(v) })
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(v.uri).videoFrameMillis(1_000).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(8.dp)),
                    )
                    Text(
                        formatDuration(v.durationMs),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(v.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(video: Video, playlists: List<String>, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("加入播放清單") },
        text = {
            Column {
                Text(video.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(8.dp))
                playlists.forEach { name ->
                    ListItem(
                        headlineContent = { Text(name) },
                        modifier = Modifier.clickable { LibraryStore.addToPlaylist(name, video.id); onDismiss() },
                    )
                }
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true,
                    placeholder = { Text("新清單名稱") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = newName.isNotBlank(), onClick = {
                LibraryStore.createPlaylist(newName.trim())
                LibraryStore.addToPlaylist(newName.trim(), video.id)
                onDismiss()
            }) { Text("建立並加入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增播放清單") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("名稱") }) },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim()); onDismiss() }) { Text("建立") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
