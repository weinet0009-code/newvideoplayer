package com.weinet.newvideoplayer.ui.library

import android.Manifest
import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
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
import com.weinet.newvideoplayer.ui.vault.VaultScreen
import com.weinet.newvideoplayer.vault.VaultSession

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (List<Video>, Int) -> Unit, vm: LibraryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permissions = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        Build.VERSION.SDK_INT >= 30 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = it[permissions[0]] == true
        if (granted) vm.refresh()
    }
    LaunchedEffect(Unit) { launcher.launch(permissions) }

    val consent by vm.consent.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        val c = vm.consent.value
        vm.consent.value = null
        c?.onResult?.invoke(r.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(consent) { consent?.let { consentLauncher.launch(IntentSenderRequest.Builder(it.sender).build()) } }
    LaunchedEffect(message) { message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show(); vm.message.value = null } }
    // Locks the private folder when leaving its tab.
    LaunchedEffect(state.tab) { if (state.tab != LibraryTab.Private) VaultSession.lock() }
    BackHandler(enabled = state.query.isNotEmpty() || state.selectedFolder != null || state.selectedPlaylist != null) { vm.back() }

    var addTarget by remember { mutableStateOf<Video?>(null) }
    var sheetFor by remember { mutableStateOf<Video?>(null) }
    var renameFor by remember { mutableStateOf<Video?>(null) }
    var deleteFor by remember { mutableStateOf<Video?>(null) }
    var vaultFor by remember { mutableStateOf<Video?>(null) }
    var detailsFor by remember { mutableStateOf<Video?>(null) }
    var showNewPlaylist by remember { mutableStateOf(false) }
    var deletePlaylist by remember { mutableStateOf<String?>(null) }

    val title = if (state.tab == LibraryTab.Private) "私密資料夾" else state.selectedFolder ?: state.selectedPlaylist ?: "影片"
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
                                LibraryTab.Private -> Icons.Default.Lock
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
                state.tab == LibraryTab.Private -> VaultScreen(onOpen = onOpen)
                !granted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { launcher.launch(permissions) }) { Text("允許讀取影片") }
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
                            state.query.isNotBlank() -> VideoList(state.searchResults, onOpen, onLongPress = { sheetFor = it })
                            state.selectedFolder != null -> VideoList(state.folderVideos, onOpen, onLongPress = { sheetFor = it })
                            else -> FolderList(state.folders, onSelect = vm::selectFolder)
                        }
                        LibraryTab.Recent ->
                            if (state.recent.isEmpty()) Empty("還沒有播放紀錄")
                            else VideoList(state.recent, onOpen, onLongPress = { sheetFor = it })
                        LibraryTab.Private -> Unit
                        LibraryTab.Playlists -> {
                            val name = state.selectedPlaylist
                            if (name == null) {
                                if (state.playlists.isEmpty()) Empty("點右上角 + 建立播放清單，長按影片加入")
                                else PlaylistList(state.playlists, onSelect = vm::selectPlaylist, onLongPress = { deletePlaylist = it })
                            } else {
                                val vids = state.playlistVideos(name)
                                if (vids.isEmpty()) Empty("長按任何影片即可加入這個清單")
                                else VideoList(vids, onOpen, onLongPress = { sheetFor = it })
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
    sheetFor?.let { v ->
        val playlistName = state.selectedPlaylist.takeIf { state.tab == LibraryTab.Playlists }
        VideoActionSheet(v, buildList {
            add(VideoAction("加入播放清單", Icons.Default.PlaylistAdd) { addTarget = v })
            if (playlistName != null) add(VideoAction("移出這個清單", Icons.Default.PlaylistRemove) { LibraryStore.removeFromPlaylist(playlistName, v.id) })
            add(VideoAction("分享", Icons.Default.Share) { shareVideo(context, v) })
            add(VideoAction("轉成音訊檔", Icons.Default.MusicNote) { vm.extractAudio(v) })
            add(VideoAction("重新命名", Icons.Default.Edit) { renameFor = v })
            add(VideoAction("詳細資訊", Icons.Default.Info) { detailsFor = v })
            add(VideoAction("移到私密資料夾", Icons.Default.Lock) { vaultFor = v })
            add(VideoAction("刪除", Icons.Default.Delete) { deleteFor = v })
        }, onDismiss = { sheetFor = null })
    }
    renameFor?.let { v -> RenameDialog(v.title, onDismiss = { renameFor = null }, onRename = { vm.rename(v, it) }) }
    deleteFor?.let { v ->
        ConfirmDialog("刪除影片？", "「${v.title}」會從手機刪除。", "刪除", onDismiss = { deleteFor = null }, onConfirm = { vm.delete(v) })
    }
    vaultFor?.let { v ->
        ConfirmDialog("移到私密資料夾？", "影片會加密存到 app 內，原檔會從相簿刪除。之後可以隨時還原。", "移動",
            onDismiss = { vaultFor = null }, onConfirm = { vm.moveToVault(v) })
    }
    detailsFor?.let { v -> DetailsDialog(context, v, onDismiss = { detailsFor = null }) }
    busy?.let { BusyDialog(it.label, it.progress) }
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
