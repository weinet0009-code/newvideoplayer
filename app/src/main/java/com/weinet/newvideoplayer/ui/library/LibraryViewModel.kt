package com.weinet.newvideoplayer.ui.library

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weinet.newvideoplayer.data.LibraryStore
import com.weinet.newvideoplayer.data.MediaActions
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.VideoRepository
import com.weinet.newvideoplayer.vault.VaultStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) { Folders("資料夾"), Recent("最近播放"), Playlists("播放清單"), Private("私密") }

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

/** A system consent dialog (Android 11+) the screen must launch, and what to do with the answer. */
class Consent(val sender: IntentSender, val onResult: (Boolean) -> Unit)

/** A long-running job shown as a progress dialog. */
data class Busy(val label: String, val progress: Float)

private data class Ui(
    val loading: Boolean = false,
    val videos: List<Video> = emptyList(),
    val tab: LibraryTab = LibraryTab.Folders,
    val selectedFolder: String? = null,
    val selectedPlaylist: String? = null,
    val query: String = "",
)

class LibraryViewModel(private val app: Application) : AndroidViewModel(app) {
    private val repo = VideoRepository(app)
    private val ui = MutableStateFlow(Ui())

    val consent = MutableStateFlow<Consent?>(null)
    val busy = MutableStateFlow<Busy?>(null)
    val message = MutableStateFlow<String?>(null)

    val state: StateFlow<LibraryState> = combine(ui, LibraryStore.recent, LibraryStore.playlists) { u, r, p ->
        LibraryState(u.loading, u.videos, u.tab, u.selectedFolder, u.selectedPlaylist, u.query, r, p)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    fun refresh() = viewModelScope.launch {
        if (ui.value.videos.isEmpty()) ui.value = ui.value.copy(loading = true)
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

    // ---- File actions ----

    private fun removeEverywhere(v: Video) {
        LibraryStore.playlists.value.keys.forEach { LibraryStore.removeFromPlaylist(it, v.id) }
    }

    /** Deletes the original; asks the system first on Android 11+. */
    private fun deleteOriginal(v: Video, done: (Boolean) -> Unit) {
        val sender = MediaActions.deleteRequest(app, v.uri)
        if (sender != null) consent.value = Consent(sender, done)
        else done(MediaActions.deleteDirect(app, v.uri))
    }

    fun delete(v: Video) = deleteOriginal(v) { ok ->
        if (ok) { removeEverywhere(v); message.value = "已刪除"; refresh() } else message.value = "無法刪除"
    }

    fun rename(v: Video, newName: String) {
        val doIt = {
            message.value = if (MediaActions.rename(app, v, newName)) "已重新命名" else "無法重新命名"
            refresh()
        }
        val sender = MediaActions.writeRequest(app, v.uri)
        if (sender != null) consent.value = Consent(sender) { ok -> if (ok) doIt() }
        else doIt()
    }

    fun moveToVault(v: Video) = viewModelScope.launch {
        busy.value = Busy("加密中…", 0f)
        val entry = try {
            VaultStore.import(app, v) { p -> busy.value = Busy("加密中…", p) }
        } catch (e: Exception) {
            busy.value = null; message.value = "加密失敗"; return@launch
        }
        busy.value = null
        deleteOriginal(v) { ok ->
            if (ok) { removeEverywhere(v); message.value = "已移到私密資料夾"; refresh() }
            else { VaultStore.delete(app, entry); message.value = "已取消，原檔保留" }
        }
    }
}
