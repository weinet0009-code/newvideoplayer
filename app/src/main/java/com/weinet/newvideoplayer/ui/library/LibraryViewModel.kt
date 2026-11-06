package com.weinet.newvideoplayer.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.VideoRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class LibraryState(
    val loading: Boolean = false,
    val videos: List<Video> = emptyList(),
    val selectedFolder: String? = null,
    val query: String = "",
) {
    val folders: Map<String, List<Video>> get() = videos.groupBy { it.folder }.toSortedMap()
    val visible: List<Video>
        get() = videos
            .filter { selectedFolder == null || it.folder == selectedFolder }
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
}

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = VideoRepository(app)
    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        _state.value = _state.value.copy(loading = false, videos = repo.loadVideos())
    }

    fun selectFolder(folder: String?) { _state.value = _state.value.copy(selectedFolder = folder) }
    fun search(q: String) { _state.value = _state.value.copy(query = q) }
}
