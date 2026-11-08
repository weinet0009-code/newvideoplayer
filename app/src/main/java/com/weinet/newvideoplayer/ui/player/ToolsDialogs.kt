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
import com.weinet.newvideoplayer.tools.GroqWhisper
import com.weinet.newvideoplayer.tools.AiResult
import com.weinet.newvideoplayer.tools.SubtitleTranslator
import com.weinet.newvideoplayer.data.SidecarSubs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AiSubtitleDialog(video: Video, onLoaded: (File, String, String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val models = remember { LocalWhisper.models(context) }
    var groqKey by remember { mutableStateOf(SecretStore.get(context, GroqWhisper.KEY_NAME) ?: "") }
    var cloud by remember { mutableStateOf(groqKey.isNotBlank()) }
    var model by remember { mutableStateOf(LocalWhisper.Model.Base) }
    var downloaded by remember { mutableStateOf(LocalWhisper.Model.entries.filter { LocalWhisper.isDownloaded(context, it) }.toSet()) }
    var lang by remember { mutableStateOf("") }
    var pickLang by remember { mutableStateOf(false) }
    var skipSilence by remember { mutableStateOf(true) }
    var accelerated by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { accelerated = withContext(Dispatchers.Default) { LocalWhisper.isAccelerated(context) } }
    var startedAt by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val working = job != null
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { SidecarSubs.rememberGrant(context, it) }
        pendingStart?.invoke(); pendingStart = null
    }

    /** Asks once for the video's folder (to save the .srt beside it), then runs [start]. */
    fun withFolder(start: () -> Unit) {
        if (SidecarSubs.needsGrant(context, video)) {
            pendingStart = start
            status = "請選擇影片所在的資料夾並允許存取，字幕會存在影片旁邊"
            runCatching { folderPicker.launch(SidecarSubs.initialUri(video)) }.onFailure { pendingStart = null; start() }
        } else start()
    }

    /** Saves the result beside the video, loads it into the player and closes. */
    fun finish(r: AiResult, tag: String, label: String) {
        if (r.file.readText().split("-->").size - 1 == 0) {
            status = "沒有辨識到任何語音，可以換個模型或語言再試"; return
        }
        val sideName = SidecarSubs.fileName(video, r.language, tag)
        val saved = SidecarSubs.save(context, video, r.file, sideName)
        android.widget.Toast.makeText(context, saved?.let { "字幕已存到 $it" } ?: "字幕已載入（未存到影片資料夾）",
            android.widget.Toast.LENGTH_LONG).show()
        onLoaded(r.file, "$label ${r.language}", sideName.takeIf { saved != null })
        onDismiss()
    }

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
        title = { Text(if (cloud) "AI 產生字幕（雲端・Beta）" else "AI 產生字幕（離線・Beta）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(bottom = 4.dp)) {
                    FilterChip(selected = !cloud, enabled = !working, onClick = { cloud = false }, label = { Text("本機") },
                        modifier = Modifier.padding(end = 4.dp))
                    FilterChip(selected = cloud, enabled = !working, onClick = { cloud = true }, label = { Text("雲端（Groq）") })
                }
                if (cloud) {
                    Text("用 Groq 的 Whisper（large-v3-turbo）在雲端辨識，一小時的影片通常幾分鐘就好。需要你自己的免費金鑰，免費額度每天 8 小時音訊。聲音會上傳到 Groq。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = groqKey, onValueChange = { groqKey = it.trim() }, singleLine = true, enabled = !working,
                        label = { Text("Groq API 金鑰") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = !working, onClick = {
                            try {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(GroqWhisper.KEYS_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) { status = "請用瀏覽器開啟 ${GroqWhisper.KEYS_URL}" }
                        }) { Text("申請免費金鑰") }
                        if (groqKey.isNotBlank()) TextButton(enabled = !working, onClick = {
                            groqKey = ""; SecretStore.set(context, GroqWhisper.KEY_NAME, null)
                        }) { Text("清除金鑰") }
                    }
                } else {
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
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("語言：${AI_LANGS.firstOrNull { it.first == lang }?.second ?: "自動"}", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f))
                    TextButton(enabled = !working, onClick = { pickLang = !pickLang }) { Text(if (pickLang) "收起" else "指定語言") }
                }
                if (pickLang) {
                    Text("自動辨識認錯時才需要指定", style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.padding(vertical = 4.dp)) {
                        AI_LANGS.forEach { (code, label) ->
                            FilterChip(selected = lang == code, enabled = !working, onClick = { lang = code }, label = { Text(label) },
                                modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
                Text("Beta：AI 字幕難免有錯字或時間偏差。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!cloud) Row(Modifier.fillMaxWidth().clickable(enabled = !working) { skipSilence = !skipSilence }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("跳過靜音（較快）")
                        Text("只辨識有人說話的段落，也比較不會亂出字", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = skipSilence, onCheckedChange = null, enabled = !working)
                }
                if (!cloud) accelerated?.let {
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
            else if (cloud) TextButton(enabled = groqKey.isNotBlank(), onClick = {
                SecretStore.set(context, GroqWhisper.KEY_NAME, groqKey)
                withFolder {
                    launchTask {
                        status = "上傳並辨識中…請不要關掉這個視窗"
                        progress = 0f
                        startedAt = System.currentTimeMillis()
                        val r = try {
                            GroqWhisper.transcribe(context, video, groqKey, lang.ifBlank { null },
                                onProgress = { progress = it },
                                onStatus = { status = it ?: "上傳並辨識中…請不要關掉這個視窗" })
                        } catch (e: GroqWhisper.InvalidKey) {
                            status = "金鑰無效，請確認後重新貼上"; null
                        } finally { startedAt = 0L }
                        if (r != null) finish(r, "Groq", "Groq 字幕")
                    }
                }
            }) { Text("開始") }
            else if (model !in downloaded) TextButton(onClick = {
                launchTask {
                    status = "下載模型中…"
                    LocalWhisper.download(context, model) { progress = it }
                    downloaded = downloaded + model
                    status = "模型已下載，可以開始"
                }
            }) { Text("下載 ${model.sizeMb} MB") }
            else TextButton(onClick = {
                withFolder {
                    launchTask {
                        status = "辨識中…可以先做別的事，但請不要關掉這個視窗"
                        progress = 0f
                        startedAt = System.currentTimeMillis()
                        val r = try {
                            LocalWhisper.transcribe(context, video, model, lang.ifBlank { null }, skipSilence) { progress = it }
                        } finally { startedAt = 0L }
                        finish(r, "AI", "AI 字幕")
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


/** Translates the chosen subtitle with Groq (online, needs the free key) or ML Kit (offline). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateSubtitleDialog(video: Video, source: File?, onLoaded: (File, String, String?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var groqKey by remember { mutableStateOf(SecretStore.get(context, GroqWhisper.KEY_NAME) ?: "") }
    var useGroq by remember { mutableStateOf(groqKey.isNotBlank()) }
    var target by remember { mutableStateOf(SubtitleTranslator.Target.ZhTw) }
    var status by remember { mutableStateOf<String?>(if (source == null) "先在字幕選單選好要翻譯的字幕（外掛、線上或 AI 產生的；內建字幕無法翻譯）" else null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    val working = job != null
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        tree?.let { SidecarSubs.rememberGrant(context, it) }
        pendingStart?.invoke(); pendingStart = null
    }

    fun start() {
        val src = source ?: return
        if (useGroq) SecretStore.set(context, GroqWhisper.KEY_NAME, groqKey)
        job = scope.launch {
            try {
                status = if (useGroq) "翻譯中…" else "離線翻譯中…"
                progress = 0f
                val out = if (useGroq) SubtitleTranslator.translateWithGroq(context, src, target, groqKey,
                    onProgress = { progress = it }, onStatus = { status = it ?: "翻譯中…" })
                else SubtitleTranslator.translateWithMlKit(context, src, target,
                    onProgress = { progress = it }, onStatus = { status = it ?: "離線翻譯中…" })
                val tag = if (useGroq) "Groq翻譯" else "MLKit翻譯"
                val sideName = SidecarSubs.fileName(video, target.code, tag)
                val saved = SidecarSubs.save(context, video, out, sideName)
                android.widget.Toast.makeText(context, saved?.let { "翻譯已存到 $it" } ?: "翻譯已載入（未存到影片資料夾）",
                    android.widget.Toast.LENGTH_LONG).show()
                onLoaded(out, "${target.label}（${if (useGroq) "Groq" else "離線"}翻譯）", sideName.takeIf { saved != null })
                onDismiss()
            } catch (e: CancellationException) { status = "已取消" }
            catch (e: GroqWhisper.InvalidKey) { status = "金鑰無效，請確認後重新貼上" }
            catch (e: Exception) { status = "失敗：${e.message}" }
            progress = null
            job = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text("翻譯字幕") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(bottom = 4.dp)) {
                    FilterChip(selected = useGroq, enabled = !working, onClick = { useGroq = true }, label = { Text("Groq（較自然）") },
                        modifier = Modifier.padding(end = 4.dp))
                    FilterChip(selected = !useGroq, enabled = !working, onClick = { useGroq = false }, label = { Text("離線（ML Kit）") })
                }
                if (useGroq) {
                    Text("用 Groq 的語言模型翻譯，免費額度每天大約夠翻幾部電影。字幕文字會傳到 Groq。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = groqKey, onValueChange = { groqKey = it.trim() }, singleLine = true, enabled = !working,
                        label = { Text("Groq API 金鑰") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !working, onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(GroqWhisper.KEYS_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text("申請免費金鑰") }
                } else {
                    Text("在手機上翻譯，免費、不用金鑰。第一次每種語言要下載約 30 MB 模型。品質較生硬，非英文之間會經過英文轉譯。",
                        style = MaterialTheme.typography.bodySmall)
                }
                Text("翻成", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.padding(vertical = 4.dp)) {
                    SubtitleTranslator.Target.entries.forEach { t ->
                        FilterChip(selected = target == t, enabled = !working, onClick = { target = t }, label = { Text(t.label) },
                            modifier = Modifier.padding(end = 4.dp))
                    }
                }
                progress?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (working) TextButton(onClick = { job?.cancel() }) { Text("停止") }
            else TextButton(enabled = source != null && (!useGroq || groqKey.isNotBlank()), onClick = {
                if (SidecarSubs.needsGrant(context, video)) {
                    pendingStart = { start() }
                    status = "請選擇影片所在的資料夾並允許存取，翻譯會存在影片旁邊"
                    runCatching { folderPicker.launch(SidecarSubs.initialUri(video)) }.onFailure { pendingStart = null; start() }
                } else start()
            }) { Text("開始") }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text("關閉") } },
    )
}


private val AI_LANGS = listOf(
    "" to "自動", "zh" to "中文", "yue" to "粵語", "en" to "英文", "ja" to "日文",
    "ko" to "韓文", "th" to "泰文", "vi" to "越南文", "es" to "西班牙文",
)
