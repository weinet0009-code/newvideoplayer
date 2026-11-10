package com.weinet.newvideoplayer.ui.library

import android.app.Application
import android.content.IntentSender
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weinet.newvideoplayer.data.LibraryStore
import com.weinet.newvideoplayer.data.MediaActions
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.VideoRepository
import com.weinet.newvideoplayer.tools.AudioExtractor
import com.weinet.newvideoplayer.vault.VaultStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

enum class LibraryTab(val label: String) { Videos("影片"), Music("音樂"), Playlists("播放清單"), Settings("設定") }
enum class VideoSub(val label: String) { Folders("資料夾"), Recent("最近播放") }
enum class MusicSub(val label: String) { Songs("歌曲"), Albums("專輯"), Artists("演出者"), Folders("資料夾") }

data class LibraryState(
    val loading: Boolean = false,
    val videos: List<Video> = emptyList(),
    val songs: List<Video> = emptyList(),
    val tab: LibraryTab = LibraryTab.Videos,
    val videoSub: VideoSub = VideoSub.Folders,
    val musicSub: MusicSub = MusicSub.Songs,
    /** The folder, album or artist opened in the current tab. */
    val selectedFolder: String? = null,
    val selectedPlaylist: String? = null,
    val query: String = "",
    val recentIds: List<Long> = emptyList(),
    val playlists: Map<String, List<Long>> = emptyMap(),
) {
    private val byId: Map<Long, Video> get() = (videos + songs).associateBy { it.id }
    private val videoById: Map<Long, Video> get() = videos.associateBy { it.id }
    val folders: Map<String, List<Video>> get() = videos.groupBy { it.folder }.toSortedMap()
    val recent: List<Video> get() = recentIds.mapNotNull { videoById[it] }
    fun playlistItems(name: String): List<Video> = playlists[name].orEmpty().mapNotNull { byId[it] }

    /** Songs grouped for the current music sub-tab (albums, artists or folders). */
    val musicGroups: Map<String, List<Video>>
        get() = when (musicSub) {
            MusicSub.Albums -> songs.groupBy { it.album.ifBlank { "未知專輯" } }
            MusicSub.Artists -> songs.groupBy { it.artist.ifBlank { "未知演出者" } }
            MusicSub.Folders -> songs.groupBy { it.folder }
            MusicSub.Songs -> emptyMap()
        }.toList().sortedBy { it.first.lowercase() }.toMap()

    val searchResults: List<Video>
        get() = if (tab == LibraryTab.Music) songs.filter {
            it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true)
        } else videos.filter { it.title.contains(query, ignoreCase = true) }

    /** What is inside the opened folder / album / artist. */
    val selectedItems: List<Video>
        get() = when {
            selectedFolder == null -> emptyList()
            tab == LibraryTab.Music -> musicGroups[selectedFolder].orEmpty().let { list ->
                if (musicSub == MusicSub.Albums) list.sortedBy { it.title.lowercase() } else list
            }
            else -> videos.filter { it.folder == selectedFolder }
        }
}

/** A system consent dialog (Android 11+) the screen must launch, and what to do with the answer. */
class Consent(val sender: IntentSender, val onResult: (Boolean) -> Unit)

/** A long-running job shown as a progress dialog. */
data class Busy(val label: String, val progress: Float)

private data class Ui(
    val loading: Boolean = false,
    val videos: List<Video> = emptyList(),
    val songs: List<Video> = emptyList(),
    val tab: LibraryTab = LibraryTab.Videos,
    val videoSub: VideoSub = VideoSub.Folders,
    val musicSub: MusicSub = MusicSub.Songs,
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
        LibraryState(u.loading, u.videos, u.songs, u.tab, u.videoSub, u.musicSub, u.selectedFolder, u.selectedPlaylist, u.query, r, p)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryState())

    fun refresh() = viewModelScope.launch {
        if (ui.value.videos.isEmpty() && ui.value.songs.isEmpty()) ui.value = ui.value.copy(loading = true)
        val videos = async { repo.loadVideos() }
        val songs = async { repo.loadAudio() }
        ui.value = ui.value.copy(loading = false, videos = videos.await(), songs = songs.await())
    }

    fun selectTab(t: LibraryTab) { ui.value = ui.value.copy(tab = t, selectedFolder = null, selectedPlaylist = null, query = "") }
    fun selectVideoSub(t: VideoSub) { ui.value = ui.value.copy(videoSub = t, selectedFolder = null, query = "") }
    fun selectMusicSub(t: MusicSub) { ui.value = ui.value.copy(musicSub = t, selectedFolder = null, query = "") }
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

    /** Encrypts every video of a folder into the vault, then deletes the originals (one system prompt on Android 11+). */
    fun moveFolderToVault(videos: List<Video>) = viewModelScope.launch {
        val list = videos.filter { !it.isAudio && it.vaultId == null }
        if (list.isEmpty()) return@launch
        val done = mutableListOf<Pair<Video, com.weinet.newvideoplayer.vault.VaultEntry>>()
        list.forEachIndexed { i, v ->
            val label = "加密中 ${i + 1}/${list.size}…"
            busy.value = Busy(label, 0f)
            runCatching { VaultStore.import(app, v) { p -> busy.value = Busy(label, p) } }.getOrNull()?.let { done += v to it }
        }
        busy.value = null
        if (done.isEmpty()) { message.value = "加密失敗"; return@launch }
        val failed = list.size - done.size
        fun report(moved: Int) {
            message.value = "已將 $moved 部影片移到私密資料夾" + if (failed > 0) "，$failed 部失敗" else ""
            refresh()
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val sender = android.provider.MediaStore.createDeleteRequest(app.contentResolver, done.map { it.first.uri }).intentSender
            consent.value = Consent(sender) { ok ->
                if (ok) { done.forEach { removeEverywhere(it.first) }; report(done.size) }
                else { done.forEach { VaultStore.delete(app, it.second) }; message.value = "已取消，原檔保留" }
            }
        } else {
            var moved = 0
            done.forEach { (v, e) ->
                if (MediaActions.deleteDirect(app, v.uri)) { removeEverywhere(v); moved++ } else VaultStore.delete(app, e)
            }
            report(moved)
        }
    }

    fun extractAudio(v: Video, mp3: Boolean) = viewModelScope.launch {
        val label = if (mp3) "轉成 MP3…" else "轉成音訊…"
        busy.value = Busy(label, 0f)
        message.value = try {
            "已存到 " + AudioExtractor.toMusic(app, v, mp3) { p -> busy.value = Busy(label, p) }
        } catch (e: Exception) { audioErrorText(e) }
        busy.value = null
    }
}
