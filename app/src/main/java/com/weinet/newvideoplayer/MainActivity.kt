package com.weinet.newvideoplayer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import android.net.Uri
import com.weinet.newvideoplayer.ui.library.LibraryScreen
import com.weinet.newvideoplayer.ui.player.PlayerScreen
import com.weinet.newvideoplayer.ui.theme.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "library") {
                    composable("library") {
                        LibraryScreen(onOpen = { video ->
                            nav.navigate("player/${video.id}/${Uri.encode(video.uri.toString())}/${Uri.encode(video.title)}")
                        })
                    }
                    composable("player/{id}/{uri}/{title}") { entry ->
                        val id = entry.arguments?.getString("id")!!.toLong()
                        val uri = Uri.parse(Uri.decode(entry.arguments?.getString("uri")!!))
                        val title = Uri.decode(entry.arguments?.getString("title") ?: "")
                        PlayerScreen(videoId = id, uri = uri, title = title, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
    }
}
