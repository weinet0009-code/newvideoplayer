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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.weinet.newvideoplayer.data.SecretStore
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.tools.AudioExtractor
import com.weinet.newvideoplayer.tools.OpenSubtitles
import com.weinet.newvideoplayer.tools.LocalWhisper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
fun AiSubtitleDialog(video: Video, onLoaded: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { LocalWhisper.models(context) }
    var model by remember { mutableStateOf(LocalWhisper.Model.Base) }
    var downloaded by remember { mutableStateOf(LocalWhisper.Model.entries.filter { LocalWhisper.isDownloaded(context, it) }.toSet()) }
    var lang by remember { mutableStateOf("zh") }
    var skipSilence by remember { mutableStateOf(true) }
    var accelerated by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { accelerated = withContext(Dispatchers.Default) { LocalWhisper.isAccelerated(context) } }
    var startedAt by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val working = job != null

    fun launchTask(block: suspend () -> Unit) {
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { status = "已取消" }
            catch (e: AudioExtractor.Unsupported) { status = "這部影片的音訊格式（${e.mime}）目前無法處理" }
            catch (e: AudioExtractor.NoAudio) { status = "這部影片沒有聲音" }
            catch (e: Exception) { status = "失敗：${e.message}" }
            progress = null
            job = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("AI 產生字幕（離線）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("在手機上辨識語音，不用網路、不用帳號，完全免費。第一次要下載辨識模型。長影片會花比較久，建議接上電源。",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text("模型", style = MaterialTheme.typography.labelLarge)
                models.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !working) { model = m }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model == m, onClick = null, enabled = !working)
                        Spacer(Modifier.width(8.dp))
                        Text("${m.label}  ${m.sizeMb} MB", modifier = Modifier.weight(1f))
                        if (m in downloaded) TextButton(enabled = !working, onClick = {
                            LocalWhisper.delete(context, m); downloaded = downloaded - m
                        }) { Text("刪除") } else Text("未下載", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (model == LocalWhisper.Model.Turbo) {
                    Text("最準但最慢，約需 1 GB 記憶體；長影片可能要好幾個小時。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Text("語言", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(vertical = 4.dp)) {
                    listOf("zh" to "中文", "en" to "英文", "ja" to "日文", "" to "自動").forEach { (code, label) ->
                        FilterChip(selected = lang == code, enabled = !working, onClick = { lang = code }, label = { Text(label) },
                            modifier = Modifier.padding(end = 4.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().clickable(enabled = !working) { skipSilence = !skipSilence }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("跳過靜音（較快）")
                        Text("只辨識有人說話的段落，也比較不會亂出字", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = skipSilence, onCheckedChange = null, enabled = !working)
                }
                accelerated?.let {
                    Text(if (it) "此手機支援加速指令，已啟用快速版" else "此手機不支援加速指令，使用通用版（較慢）",
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                progress?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                val p = progress
                if (working && startedAt > 0 && p != null) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    val pct = (p * 100).toInt().coerceIn(0, 100)
                    Text(if (p < 0.02f || elapsed < 15_000) "$pct%・估算剩餘時間中…" else "$pct%・剩下約 ${formatEta(((elapsed / p) * (1 - p)).toLong())}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (working && progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (working) TextButton(onClick = { LocalWhisper.cancel(); job?.cancel() }) { Text("停止") }
            else if (model !in downloaded) TextButton(onClick = {
                launchTask {
                    status = "下載模型中…"
                    LocalWhisper.download(context, model) { progress = it }
                    downloaded = downloaded + model
                    status = "模型已下載，可以開始"
                }
            }) { Text("下載 ${model.sizeMb} MB") }
            else TextButton(onClick = {
                launchTask {
                    status = "辨識中…可以先做別的事，但請不要關掉這個視窗"
                    progress = 0f
                    startedAt = System.currentTimeMillis()
                    val srt = try {
                        LocalWhisper.transcribe(context, video, model, lang.ifBlank { null }, skipSilence) { progress = it }
                    } finally { startedAt = 0L }
                    val lines = srt.readText().split("-->").size - 1
                    if (lines == 0) {
                        status = "沒有辨識到任何語音，可以換個模型或語言再試"
                    } else {
                        onLoaded(srt)
                        onDismiss()
                    }
                }
            }) { Text("開始") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("關閉") } },
    )
}

private fun formatEta(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min < 60) "$min 分鐘" else "${min / 60} 小時 ${min % 60} 分"
}

@Composable
fun ApiSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var osUser by remember { mutableStateOf(SecretStore.get(context, KEY_OS_USER) ?: "") }
    var osPass by remember { mutableStateOf("") }
    var osSignedIn by remember { mutableStateOf(SecretStore.get(context, KEY_OS_TOKEN) != null) }
    var status by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenSubtitles 帳號") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("選填。未登入每天可下載 5 個，登入後用帳號的額度。", style = MaterialTheme.typography.bodySmall)
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}
