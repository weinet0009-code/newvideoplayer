package com.weinet.newvideoplayer.ui.library

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.formatDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpen: (Video) -> Unit, vm: LibraryViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        if (it) vm.refresh()
    }
    LaunchedEffect(Unit) { launcher.launch(permission) }
    BackHandler(enabled = state.selectedFolder != null) { vm.selectFolder(null) }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(state.selectedFolder ?: "影片") },
            navigationIcon = {
                if (state.selectedFolder != null) IconButton(onClick = { vm.selectFolder(null) }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                }
            },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !granted -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = { launcher.launch(permission) }) { Text("允許讀取影片") }
                }
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    OutlinedTextField(
                        value = state.query, onValueChange = vm::search,
                        placeholder = { Text("搜尋影片") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    if (state.selectedFolder == null && state.query.isBlank()) {
                        FolderList(state.folders, onSelect = vm::selectFolder)
                    } else {
                        VideoList(state.visible, onOpen)
                    }
                }
            }
        }
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

@Composable
private fun VideoList(videos: List<Video>, onOpen: (Video) -> Unit) {
    LazyColumn {
        items(videos, key = { it.id }) { v ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(v) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    AsyncImage(
                        model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                            .data(v.uri).videoFrameMillis(1_000).build(),
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
