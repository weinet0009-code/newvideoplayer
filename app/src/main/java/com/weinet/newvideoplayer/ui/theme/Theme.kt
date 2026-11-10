package com.weinet.newvideoplayer.ui.theme

import android.content.Context
import android.graphics.Color as AColor
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** A colour set: dark ones use white text, light ones black text. */
enum class Palette(val label: String, val dark: Boolean, val bg: Long, val surface: Long, val accent: Long, val onAccent: Long) {
    Obsidian("曜石黑", true, 0xFF000000, 0xFF0A0A0A, 0xFF8AB4F8, 0xFF00224D),
    BlackGold("黑金", true, 0xFF0E0D0B, 0xFF17150F, 0xFFD4AF37, 0xFF1A1400),
    DeepSea("深海藍", true, 0xFF0A1626, 0xFF0F1E33, 0xFF4FC3F7, 0xFF002233),
    Graphite("石墨灰", true, 0xFF1E1F22, 0xFF26282C, 0xFF9FA8DA, 0xFF1A1F4D),
    White("純白", false, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF3D7BFF, 0xFFFFFFFF),
    Ivory("米白", false, 0xFFFAF6EE, 0xFFFFFBF3, 0xFF8D6E2F, 0xFFFFFFFF),
    Mist("霧藍", false, 0xFFEEF3F8, 0xFFF6F9FC, 0xFF3A6EA5, 0xFFFFFFFF),
}

enum class ThemeMode(val label: String) { Auto("自動"), Fixed("配色"), Custom("自由配色"), Image("自選圖片") }

data class ThemeConfig(
    val mode: ThemeMode = ThemeMode.Auto,
    val palette: Palette = Palette.BlackGold,
    val autoDark: Palette = Palette.BlackGold,
    val autoLight: Palette = Palette.White,
    /** Background picture copied into app storage (only for [ThemeMode.Image]). */
    val imagePath: String? = null,
    /** White text over the picture (dark overlay) or black text (light overlay). */
    val imageDarkText: Boolean = false,
    /** Overlay strength 0..0.85 so text stays readable. */
    val imageScrim: Float = 0.45f,
    /** Free colours (only for [ThemeMode.Custom]), ARGB. */
    val customBg: Long = 0xFF14181F,
    val customText: Long = 0xFFF2F2F2,
)

/** Saved theme choice; changes apply right away. */
object ThemeStore {
    private val _state = MutableStateFlow(ThemeConfig())
    val state: StateFlow<ThemeConfig> = _state
    private var loaded = false

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("theme", Context.MODE_PRIVATE)

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = prefs(context)
        fun pal(key: String, def: Palette) = runCatching { Palette.valueOf(p.getString(key, def.name)!!) }.getOrDefault(def)
        _state.value = ThemeConfig(
            mode = runCatching { ThemeMode.valueOf(p.getString("mode", ThemeMode.Auto.name)!!) }.getOrDefault(ThemeMode.Auto),
            palette = pal("palette", Palette.BlackGold),
            autoDark = pal("autoDark", Palette.BlackGold),
            autoLight = pal("autoLight", Palette.White),
            imagePath = p.getString("image", null)?.takeIf { File(it).exists() },
            imageDarkText = !p.getBoolean("imageWhiteText", true),
            imageScrim = p.getFloat("imageScrim", 0.45f),
            customBg = p.getLong("customBg", 0xFF14181F),
            customText = p.getLong("customText", 0xFFF2F2F2),
        )
    }

    fun update(context: Context, c: ThemeConfig) {
        _state.value = c
        prefs(context).edit()
            .putString("mode", c.mode.name).putString("palette", c.palette.name)
            .putString("autoDark", c.autoDark.name).putString("autoLight", c.autoLight.name)
            .putString("image", c.imagePath).putBoolean("imageWhiteText", !c.imageDarkText)
            .putFloat("imageScrim", c.imageScrim)
            .putLong("customBg", c.customBg).putLong("customText", c.customText).apply()
    }

    /** Copies the picked picture into app storage (so it survives the gallery permission) and returns its path. */
    fun importImage(context: Context, uri: Uri): String? = runCatching {
        val dir = File(context.filesDir, "theme").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "bg_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { i.copyTo(it) } }
        out.path
    }.getOrNull()
}

private fun scheme(p: Palette): ColorScheme =
    schemeOf(p.dark, Color(p.bg), Color(p.surface), Color(p.accent), Color(p.onAccent), if (p.dark) Color.White else Color.Black)

/** Free colours: dark or light follows the background's brightness; the accent stays a readable blue. */
fun isDarkColor(c: Long) = Color(c).luminance() < 0.4f

private fun customScheme(bgL: Long, textL: Long): ColorScheme {
    val bg = Color(bgL); val text = Color(textL); val dark = isDarkColor(bgL)
    val accent = if (dark) Color(0xFF8AB4F8) else Color(0xFF2F6BE0)
    return schemeOf(dark, bg, lerp(bg, text, 0.04f), accent, if (dark) Color(0xFF00224D) else Color.White, text)
}

private fun schemeOf(dark: Boolean, bg: Color, surface: Color, accent: Color, onAccent: Color, text: Color): ColorScheme {
    val toward = if (dark) Color.White else Color.Black
    fun s(f: Float) = lerp(surface, toward, f)
    val muted = lerp(text, bg, 0.25f)
    return if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = lerp(surface, accent, 0.30f), onPrimaryContainer = text,
        secondaryContainer = lerp(surface, accent, 0.22f), onSecondaryContainer = text,
        background = bg, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = s(0.10f), onSurfaceVariant = muted,
        surfaceContainerLowest = bg, surfaceContainerLow = s(0.03f), surfaceContainer = s(0.06f),
        surfaceContainerHigh = s(0.09f), surfaceContainerHighest = s(0.13f),
        outline = s(0.35f), outlineVariant = s(0.18f),
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent,
        primaryContainer = lerp(surface, accent, 0.18f), onPrimaryContainer = text,
        secondaryContainer = lerp(surface, accent, 0.14f), onSecondaryContainer = text,
        background = bg, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = s(0.06f), onSurfaceVariant = muted,
        surfaceContainerLowest = lerp(bg, Color.White, 0.5f), surfaceContainerLow = s(0.02f), surfaceContainer = s(0.04f),
        surfaceContainerHigh = s(0.06f), surfaceContainerHighest = s(0.09f),
        outline = s(0.40f), outlineVariant = s(0.15f),
    )
}

/** Picture mode: background and surfaces see-through so the picture shows; dialogs and menus stay solid. */
private fun imageScheme(darkText: Boolean): ColorScheme {
    val base = scheme(if (darkText) Palette.White else Palette.Graphite)
    return base.copy(
        background = Color.Transparent,
        surface = Color.Transparent,
        surfaceContainer = base.surfaceContainer.copy(alpha = 0.72f),
    )
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    ThemeStore.load(context)
    val cfg by ThemeStore.state.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val (colors, dark) = when (cfg.mode) {
        ThemeMode.Fixed -> scheme(cfg.palette) to cfg.palette.dark
        ThemeMode.Auto -> (if (systemDark) cfg.autoDark else cfg.autoLight).let { scheme(it) to it.dark }
        ThemeMode.Custom -> customScheme(cfg.customBg, cfg.customText) to isDarkColor(cfg.customBg)
        ThemeMode.Image -> if (cfg.imagePath != null) imageScheme(cfg.imageDarkText) to !cfg.imageDarkText
        else scheme(cfg.autoDark) to true
    }
    LaunchedEffect(dark) {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            if (dark) SystemBarStyle.dark(AColor.TRANSPARENT)
            else SystemBarStyle.light(AColor.TRANSPARENT, AColor.TRANSPARENT),
        )
    }
    MaterialTheme(colorScheme = colors) {
        if (cfg.mode == ThemeMode.Image && cfg.imagePath != null) {
            Box(Modifier.fillMaxSize().background(if (cfg.imageDarkText) Color.White else Color.Black)) {
                AsyncImage(model = File(cfg.imagePath!!), contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background((if (cfg.imageDarkText) Color.White else Color.Black).copy(alpha = cfg.imageScrim)))
                content()
            }
        } else content()
    }
}
