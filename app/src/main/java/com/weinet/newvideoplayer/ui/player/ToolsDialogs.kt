package com.weinet.newvideoplayer.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weinet.newvideoplayer.data.SecretStore
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.tools.AudioExtractor
import com.weinet.newvideoplayer.tools.OpenSubtitles
import com.weinet.newvideoplayer.tools.Whisper
import kotlinx.coroutines.launch
import java.io.File

const val KEY_OPENAI = "openai_key"
const val KEY_OS_TOKEN = "opensubtitles_token"
const val KEY_OS_USER = "opensubtitles_user"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineSubtitleDialog(video: Video, onLoaded: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf(OpenSubtitles.queryFor(video.title)) }
    var lang by remember { mutableStateOf("en,zh-cn,zh-tw") }
    var results by remember { mutableStateOf<List<OpenSubtitles.Result>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    fun search() {
        working = true; status = null
        scope.launch {
            try {
                results = OpenSubtitles.search(query, lang)
                if (results.isEmpty()) status = "找不到字幕，試試改片名"
            } catch (e: Exception) { status = "搜尋失敗：${e.message}" }
            working = false
        }
    }
    LaunchedEffect(Unit) { if (OpenSubtitles.available && query.isNotBlank()) search() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("關閉") } },
        title = { Text("線上字幕") },
        text = {
            Column {
                if (!OpenSubtitles.available) {
                    Text("這個版本沒有內建 OpenSubtitles 金鑰。建置時設定 OPENSUBTITLES_API_KEY 後就能使用。")
                    return@Column
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text("片名") },
                    trailingIcon = { IconButton(onClick = { search() }) { Icon(Icons.Default.Search, "搜尋") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(vertical = 4.dp)) {
                    listOf("en,zh-cn,zh-tw" to "中英", "zh-tw" to "繁中", "zh-cn" to "簡中", "en" to "英文").forEach { (code, label) ->
                        FilterChip(selected = lang == code, onClick = { lang = code; search() }, label = { Text(label) },
                            modifier = Modifier.padding(end = 4.dp))
                    }
                }
                if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(results) { r ->
                        ListItem(
                            headlineContent = { Text(r.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text("${r.language} · 下載 ${r.downloads} 次") },
                            modifier = Modifier.clickable(enabled = !working) {
                                working = true
                                scope.launch {
                                    try {
                                        onLoaded(OpenSubtitles.download(context, r, SecretStore.get(context, KEY_OS_TOKEN)))
                                        onDismiss()
                                    } catch (e: Exception) { status = "下載失敗：${e.message}" }
                                    working = false
                                }
                            },
                        )
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSubtitleDialog(video: Video, onLoaded: (File) -> Unit, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val key = remember { SecretStore.get(context, KEY_OPENAI) }
    var lang by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("AI 產生字幕") },
        text = {
            Column {
                if (key == null) {
                    Text("需要 OpenAI API 金鑰。金鑰只存在這支手機，並加密保存。")
                    return@Column
                }
                Text("會把影片的聲音上傳到 OpenAI 辨識（whisper-1），完成後自動載入字幕。音訊需小於 25 MB（約 25–40 分鐘）。",
                    style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(vertical = 8.dp)) {
                    listOf("" to "自動", "zh" to "中文", "en" to "英文", "ja" to "日文").forEach { (code, label) ->
                        FilterChip(selected = lang == code, enabled = !working, onClick = { lang = code }, label = { Text(label) },
                            modifier = Modifier.padding(end = 4.dp))
                    }
                }
                progress?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                if (working && progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (key == null) TextButton(onClick = { onDismiss(); onOpenSettings() }) { Text("設定金鑰") }
            else TextButton(enabled = !working, onClick = {
                working = true
                scope.launch {
                    try {
                        status = "擷取音訊…"
                        val audio = AudioExtractor.toCache(context, video) { progress = it }
                        progress = null
                        if (audio.length() > Whisper.MAX_BYTES) error("音訊超過 25 MB，影片太長")
                        status = "上傳並辨識中，可能要幾分鐘…"
                        val srt = Whisper.transcribe(context, key, audio, lang.ifBlank { null })
                        audio.delete()
                        onLoaded(srt)
                        onDismiss()
                    } catch (e: AudioExtractor.Unsupported) {
                        status = "這部影片的音訊格式（${e.mime}）目前無法處理"
                    } catch (e: AudioExtractor.NoAudio) {
                        status = "這部影片沒有聲音"
                    } catch (e: Exception) {
                        status = "失敗：${e.message}"
                    }
                    progress = null
                    working = false
                }
            }) { Text("開始") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun ApiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var openAi by remember { mutableStateOf(SecretStore.get(context, KEY_OPENAI) ?: "") }
    var osUser by remember { mutableStateOf(SecretStore.get(context, KEY_OS_USER) ?: "") }
    var osPass by remember { mutableStateOf("") }
    var osSignedIn by remember { mutableStateOf(SecretStore.get(context, KEY_OS_TOKEN) != null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("字幕服務設定") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("OpenAI API 金鑰（AI 字幕）", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = openAi, onValueChange = { openAi = it.trim() }, singleLine = true,
                    placeholder = { Text("sk-…") }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Text("OpenSubtitles 帳號（選填，提高每日下載數）", style = MaterialTheme.typography.labelLarge)
                if (osSignedIn) {
                    Row {
                        Text("已登入：$osUser", modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = {
                            SecretStore.set(context, KEY_OS_TOKEN, null); osSignedIn = false
                        }) { Text("登出") }
                    }
                } else {
                    OutlinedTextField(value = osUser, onValueChange = { osUser = it }, singleLine = true,
                        label = { Text("帳號") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = osPass, onValueChange = { osPass = it }, singleLine = true,
                        label = { Text("密碼") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !working && OpenSubtitles.available && osUser.isNotBlank() && osPass.isNotBlank(), onClick = {
                        working = true
                        scope.launch {
                            try {
                                val token = OpenSubtitles.login(osUser, osPass)
                                SecretStore.set(context, KEY_OS_TOKEN, token)
                                SecretStore.set(context, KEY_OS_USER, osUser)
                                osPass = ""; osSignedIn = true; status = "OpenSubtitles 已登入"
                            } catch (e: Exception) { status = "登入失敗：${e.message}" }
                            working = false
                        }
                    }) { Text("登入") }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = { SecretStore.set(context, KEY_OPENAI, openAi); onDismiss() }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
