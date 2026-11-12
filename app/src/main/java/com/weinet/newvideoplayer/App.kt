package com.weinet.newvideoplayer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import com.weinet.newvideoplayer.data.LibraryStore

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        com.weinet.newvideoplayer.playback.AudioEq.init(this)
        LibraryStore.init(this)
    }

    // Lets Coil draw video thumbnails.
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
}
