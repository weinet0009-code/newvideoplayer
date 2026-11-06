package com.weinet.newvideoplayer.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weinet.newvideoplayer.data.LibraryStore
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) { Folders("資料夾"), Recent("最近播放"), Playlists("播放清單") }

data class LibraryState(
    val loading: Boolean = false,
    val videos: List<Video> = emptyList(),
    val tab: LibraryTab = LibraryTab.Folders,
    val selectedFolder: String? = null,
    val selectedPlaylist: String? = null,
    val query: String = "",
    val recentIds: List<Long> = emptyList(),
    val playlists: Map<String, List<Long>> = emptyMap(),
) {
    private val byId: Map<Long, Video> get() = videos.associateBy { it.id }
    val folders: Map<String, List<Video>> get() = videos.groupBy { it.folder }.toSortedMap()
    val recent: List<Video> get() = recentIds.mapNotNull { byId[it] }
    fun playlistVideos(name: String): List<Video> = playlists[name].orEmpty().mapNotNull { byId[it] }

    val searchResults: List<Video>
        get() = videos.filter { it.title.contains(query, ignoreCase = true) }
    val folderVideos: List<Video>
        get() = videos.filter { it.folder == selectedFolder }
}

private data class Ui(
    val loading: Boolean = false,
    val videos: List<Video> = emptyList(),
    val tab: LibraryTab = LibraryTab.Folders,
    val selectedFolder: String? = null,
    val selectedPlaylist: String? = null,
    val query: String = "",
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = VideoRepository(app)
    private val ui = MutableStateFlow(Ui())

    val state: StateFlow<LibraryState> = combine(ui, LibraryStore.recent, LibraryStore.playlists) { u, r, p ->
        LibraryState(u.loading, u.videos, u.tab, u.selectedFolder, u.selectedPlaylist, u.query, r, p)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    fun refresh() = viewModelScope.launch {
        ui.value = ui.value.copy(loading = true)
        ui.value = ui.value.copy(loading = false, videos = repo.loadVideos())
    }

    fun selectTab(t: LibraryTab) { ui.value = ui.value.copy(tab = t, selectedFolder = null, selectedPlaylist = null) }
    fun selectFolder(folder: String?) { ui.value = ui.value.copy(selectedFolder = folder) }
    fun selectPlaylist(name: String?) { ui.value = ui.value.copy(selectedPlaylist = name) }
    fun search(q: String) { ui.value = ui.value.copy(query = q) }

    /** Clears the innermost selection; returns false when there was nothing to go back from. */
    fun back(): Boolean {
        val u = ui.value
        return when {
            u.query.isNotEmpty() -> { search(""); true }
            u.selectedFolder != null -> { selectFolder(null); true }
            u.selectedPlaylist != null -> { selectPlaylist(null); true }
            else -> false
        }
    }
}
