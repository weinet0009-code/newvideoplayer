package com.weinet.newvideoplayer.playback

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.weinet.newvideoplayer.MainActivity
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Background playback (media notification + lock screen controls) and the floating video window. */
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var floatRoot: View? = null
    private var floatPlayerView: PlayerView? = null
    private val scope = kotlinx.coroutines.MainScope()

    override fun onCreate() {
        super.onCreate()
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // Follow the shared player, including when it is rebuilt after a decoder switch.
        scope.launch {
            PlayerHolder.player.filterNotNull().collect { p ->
                val s = session
                if (s == null) session = MediaSession.Builder(this@PlaybackService, p).setSessionActivity(openApp).build()
                else if (s.player !== p) s.player = p
                floatPlayerView?.player = p
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW_FLOAT -> showFloat()
            ACTION_HIDE_FLOAT -> hideFloat()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) {
            hideFloat()
            PlayerHolder.stop(this)
            stopSelf()
        }
    }

    override fun onDestroy() {
        hideFloat()
        scope.cancel()
        session?.release()
        session = null
        super.onDestroy()
    }

    // ---- Floating window ----

    @OptIn(UnstableApi::class)
    @SuppressLint("ClickableViewAccessibility")
    private fun showFloat() {
        if (floatRoot != null || !Settings.canDrawOverlays(this)) return
        val player = PlayerHolder.player.value ?: return
        val wm = getSystemService(WindowManager::class.java)
        val density = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val video = player.videoSize
        val ratio = if (video.width > 0 && video.height > 0) video.width.toFloat() / video.height else 16f / 9f

        var width = (screenW * 0.6f).roundToInt()
        val params = WindowManager.LayoutParams(
            width, (width / ratio).roundToInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START; x = (16 * density).toInt(); y = (120 * density).toInt() }

        val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material)
        val pv = PlayerView(ctx).apply {
            useController = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShutterBackgroundColor(Color.BLACK)
            this.player = player
        }
        val btn = (32 * density).toInt()
        val close = ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { PlayerHolder.stop(this@PlaybackService); hideFloat() }
        }
        val playPause = ImageView(ctx).apply {
            setBackgroundColor(0x88000000.toInt())
            fun refresh() = setImageResource(if (player.isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            refresh()
            setOnClickListener { player.playWhenReady = !player.playWhenReady; postDelayed({ refresh() }, 150) }
        }
        val expand = ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_revert)
            setBackgroundColor(0x88000000.toInt())
            setOnClickListener { openApp() }
        }
        val root = FrameLayout(ctx).apply {
            setBackgroundColor(Color.BLACK)
            addView(pv, FrameLayout.LayoutParams(-1, -1))
            addView(close, FrameLayout.LayoutParams(btn, btn, Gravity.TOP or Gravity.END))
            addView(expand, FrameLayout.LayoutParams(btn, btn, Gravity.TOP or Gravity.START))
            addView(playPause, FrameLayout.LayoutParams(btn, btn, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        }

        // Drag to move, pinch to resize, double tap to return to the app.
        val minW = (160 * density).toInt()
        val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                width = (width * d.scaleFactor).roundToInt().coerceIn(minW, screenW)
                params.width = width
                params.height = (width / ratio).roundToInt()
                wm.updateViewLayout(root, params)
                return true
            }
        })
        val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { openApp(); return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scale.isInProgress) return false
                params.x -= dx.roundToInt(); params.y -= dy.roundToInt()
                wm.updateViewLayout(root, params)
                return true
            }
        })
        pv.setOnTouchListener { _, e -> scale.onTouchEvent(e); gestures.onTouchEvent(e); true }

        wm.addView(root, params)
        floatRoot = root
        floatPlayerView = pv
        PlayerHolder.floating = true
    }

    private fun hideFloat() {
        val root = floatRoot ?: return
        floatPlayerView?.player = null
        getSystemService(WindowManager::class.java).removeViewImmediate(root)
        floatRoot = null
        floatPlayerView = null
        PlayerHolder.floating = false
    }

    private fun openApp() {
        hideFloat()
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PLAYER, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    companion object {
        const val ACTION_SHOW_FLOAT = "com.weinet.newvideoplayer.SHOW_FLOAT"
        const val ACTION_HIDE_FLOAT = "com.weinet.newvideoplayer.HIDE_FLOAT"

        fun showFloat(context: Context) =
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_SHOW_FLOAT))

        fun hideFloat(context: Context) =
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_HIDE_FLOAT))
    }
}
