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
                Row {
                    ThemeMode.entries.forEach { m ->
                        FilterChip(selected = cfg.mode == m, onClick = {
                            if (m == ThemeMode.Image && cfg.imagePath == null)
                                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            else set(cfg.copy(mode = m))
                        }, label = { Text(if (m == ThemeMode.Auto) "自動" else m.label) }, modifier = Modifier.padding(end = 4.dp))
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
