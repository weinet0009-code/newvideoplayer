package com.weinet.newvideoplayer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.weinet.newvideoplayer.playback.PipState
import com.weinet.newvideoplayer.playback.PlaybackService
import com.weinet.newvideoplayer.playback.PlayerHolder
import com.weinet.newvideoplayer.ui.library.LibraryScreen
import com.weinet.newvideoplayer.ui.player.PlayerScreen
import com.weinet.newvideoplayer.ui.theme.AppTheme

class MainActivity : ComponentActivity() {

    /** Set when the floating window or the notification asks to reopen the player. */
    private val openPlayerRequest = mutableStateOf(0)

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> {
        PipState.inPip.value = it.isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        addOnPictureInPictureModeChangedListener(pipListener)
        handleIntent(intent)
        setContent {
            AppTheme {
                val nav = rememberNavController()
                LaunchedEffect(openPlayerRequest.value) {
                    if (openPlayerRequest.value > 0 && PlayerHolder.player.value?.mediaItemCount ?: 0 > 0 &&
                        nav.currentDestination?.route != "player") nav.navigate("player")
                }
                NavHost(navController = nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(onOpen = { videos, index ->
                            PlayerHolder.start(this@MainActivity, videos, index)
                            nav.navigate("player")
                        })
                    }
                    composable("player") {
                        PlayerScreen(onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_PLAYER, false) == true) {
            intent.removeExtra(EXTRA_OPEN_PLAYER)
            if (PlayerHolder.floating) PlaybackService.hideFloat(this)
            openPlayerRequest.value++
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        PipState.onUserLeave?.invoke()
    }

    override fun onDestroy() {
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
