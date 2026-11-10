package com.weinet.newvideoplayer.ui.theme

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ThemeDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg by ThemeStore.state.collectAsState()
    fun set(c: ThemeConfig) = ThemeStore.update(context, c)
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) { ThemeStore.importImage(context, uri) }
            if (path != null) set(ThemeStore.state.value.copy(mode = ThemeMode.Image, imagePath = path))
        }
    }

    @Composable
    fun Swatch(p: Palette, selected: Boolean, onClick: () -> Unit) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(4.dp).width(64.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Color(p.bg))
                .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Gray), CircleShape),
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(Color(p.accent)))
            }
            Text(p.label, style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("佈景主題") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(selected = cfg.mode == m, onClick = {
                            if (m == ThemeMode.Image && cfg.imagePath == null)
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            else set(cfg.copy(mode = m))
                        }, label = { Text(m.label) }, modifier = Modifier.padding(end = 4.dp))
                    }
                }
                when (cfg.mode) {
                    ThemeMode.Fixed -> {
                        Text("深色・白字", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow { Palette.entries.filter { it.dark }.forEach { p -> Swatch(p, cfg.palette == p) { set(cfg.copy(palette = p)) } } }
                        Text("淺色・黑字", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow { Palette.entries.filter { !it.dark }.forEach { p -> Swatch(p, cfg.palette == p) { set(cfg.copy(palette = p)) } } }
                    }
                    ThemeMode.Auto -> {
                        Text("手機切到深色模式時", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow { Palette.entries.filter { it.dark }.forEach { p -> Swatch(p, cfg.autoDark == p) { set(cfg.copy(autoDark = p)) } } }
                        Text("手機切到淺色模式時", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        FlowRow { Palette.entries.filter { !it.dark }.forEach { p -> Swatch(p, cfg.autoLight == p) { set(cfg.copy(autoLight = p)) } } }
                    }
                    ThemeMode.Custom -> {
                        Text("預覽", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp))
                            .background(Color(cfg.customBg)).padding(12.dp)) {
                            Text("影片 · 音樂 · 播放清單", color = Color(cfg.customText))
                        }
                        val ratio = ColorUtils.calculateContrast(cfg.customText.toInt(), cfg.customBg.toInt() or 0xFF000000.toInt())
                        if (ratio < 4.5) Text("文字和背景太接近，可能不易閱讀", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                        ColorEditor("背景顏色", cfg.customBg, BG_PRESETS) { set(ThemeStore.state.value.copy(customBg = it)) }
                        ColorEditor("文字顏色", cfg.customText, TEXT_PRESETS) { set(ThemeStore.state.value.copy(customText = it)) }
                        Text("按鈕顏色依背景深淺自動調整。播放畫面一律維持黑底。", style = MaterialTheme.typography.bodySmall)
                    }
                    ThemeMode.Image -> {
                        TextButton(onClick = {
                            pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }) { Text(if (cfg.imagePath == null) "選擇圖片" else "換一張圖片") }
                        Text("文字顏色", style = MaterialTheme.typography.labelLarge)
                        Row {
                            FilterChip(selected = !cfg.imageDarkText, onClick = { set(cfg.copy(imageDarkText = false)) },
                                label = { Text("白字") }, modifier = Modifier.padding(end = 4.dp))
                            FilterChip(selected = cfg.imageDarkText, onClick = { set(cfg.copy(imageDarkText = true)) },
                                label = { Text("黑字") })
                        }
                        Text(if (cfg.imageDarkText) "圖片亮化程度" else "圖片暗化程度", style = MaterialTheme.typography.labelLarge)
                        Slider(value = cfg.imageScrim, onValueChange = { set(cfg.copy(imageScrim = it)) }, valueRange = 0f..0.85f)
                        Text("看不清楚字時把這個調高。播放畫面一律維持黑底。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
    )
}

private val BG_PRESETS = listOf(0xFF000000, 0xFF14181F, 0xFF1B2A1E, 0xFF2A1B2E, 0xFF3A2418, 0xFF102A43,
    0xFFFFFFFF, 0xFFF5F0E6, 0xFFE8F0E3, 0xFFF3E8F5, 0xFFFCE8E0, 0xFFE3EEF8)
private val TEXT_PRESETS = listOf(0xFFFFFFFF, 0xFFF2F2F2, 0xFFFFE9B0, 0xFFC8E6C9, 0xFF000000, 0xFF333333, 0xFF1F3A5F, 0xFF4A2C10)

/** Quick swatches plus hue / saturation / brightness sliders. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColorEditor(label: String, color: Long, presets: List<Long>, onChange: (Long) -> Unit) {
    val hsv = remember(label) { FloatArray(3).also { android.graphics.Color.colorToHSV(color.toInt(), it) } }
    var h by remember(label) { mutableFloatStateOf(hsv[0]) }
    var sat by remember(label) { mutableFloatStateOf(hsv[1]) }
    var v by remember(label) { mutableFloatStateOf(hsv[2]) }
    fun emit() = onChange(android.graphics.Color.HSVToColor(floatArrayOf(h, sat, v)).toLong() and 0xFFFFFFFFL)
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
    FlowRow {
        presets.forEach { c ->
            Box(Modifier.padding(3.dp).size(28.dp).clip(CircleShape).background(Color(c))
                .border(BorderStroke(if (c == color) 3.dp else 1.dp, if (c == color) MaterialTheme.colorScheme.primary else Color.Gray), CircleShape)
                .clickable {
                    val a = FloatArray(3); android.graphics.Color.colorToHSV(c.toInt(), a)
                    h = a[0]; sat = a[1]; v = a[2]; onChange(c)
                })
        }
    }
    Text("色相", style = MaterialTheme.typography.bodySmall)
    Slider(value = h, onValueChange = { h = it; emit() }, valueRange = 0f..360f)
    Text("飽和度", style = MaterialTheme.typography.bodySmall)
    Slider(value = sat, onValueChange = { sat = it; emit() })
    Text("亮度", style = MaterialTheme.typography.bodySmall)
    Slider(value = v, onValueChange = { v = it; emit() })
}
