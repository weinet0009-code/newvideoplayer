package com.weinet.newvideoplayer.ui.player

import com.weinet.newvideoplayer.tr
import com.weinet.newvideoplayer.R

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
                if (results.isEmpty()) status = tr(R.string.s205)
            } catch (e: Exception) { status = tr(R.string.s206, e.message) }
            working = false
        }
    }
    LaunchedEffect(Unit) { if (OpenSubtitles.available && query.isNotBlank()) search() }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s002)) } },
        title = { Text(tr(R.string.s207)) },
        text = {
            Column {
                if (!OpenSubtitles.available) {
                    Text(tr(R.string.s208))
                    return@Column
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true, label = { Text(tr(R.string.s209)) },
                    trailingIcon = { IconButton(onClick = { search() }) { Icon(Icons.Default.Search, tr(R.string.s210)) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(vertical = 4.dp)) {
                    listOf("en,zh-cn,zh-tw" to tr(R.string.s211), "zh-tw" to tr(R.string.s212), "zh-cn" to tr(R.string.s213), "en" to tr(R.string.s018)).forEach { (code, label) ->
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
                            supportingContent = { Text(tr(R.string.s214, r.language, r.downloads)) },
                            modifier = Modifier.clickable(enabled = !working) {
                                working = true
                                scope.launch {
                                    try {
                                        onLoaded(OpenSubtitles.download(context, r, SecretStore.get(context, KEY_OS_TOKEN)))
                                        onDismiss()
                                    } catch (e: Exception) { status = tr(R.string.s215, e.message) }
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
            status = tr(R.string.s216)
            runCatching { folderPicker.launch(SidecarSubs.initialUri(video)) }.onFailure { pendingStart = null; start() }
        } else start()
    }

    /** Saves the result beside the video, loads it into the player and closes. */
    fun finish(r: AiResult, tag: String, label: String) {
        if (r.file.readText().split("-->").size - 1 == 0) {
            status = tr(R.string.s217); return
        }
        val sideName = SidecarSubs.fileName(video, r.language, tag)
        val saved = SidecarSubs.save(context, video, r.file, sideName)
        android.widget.Toast.makeText(context, saved?.let { tr(R.string.s218, it) } ?: tr(R.string.s219),
            android.widget.Toast.LENGTH_LONG).show()
        onLoaded(r.file, "$label ${r.language}", sideName.takeIf { saved != null })
        onDismiss()
    }

    fun launchTask(block: suspend () -> Unit) {
        job = scope.launch {
            try { block() }
            catch (e: CancellationException) { status = tr(R.string.s220) }
            catch (e: AudioExtractor.Unsupported) { status = tr(R.string.s221, e.mime) }
            catch (e: AudioExtractor.NoAudio) { status = tr(R.string.s117) }
            catch (e: Exception) { status = tr(R.string.s222, e.message) }
            progress = null
            job = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(if (cloud) tr(R.string.s223) else tr(R.string.s224)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(bottom = 4.dp)) {
                    FilterChip(selected = !cloud, enabled = !working, onClick = { cloud = false }, label = { Text(tr(R.string.s225)) },
                        modifier = Modifier.padding(end = 4.dp))
                    FilterChip(selected = cloud, enabled = !working, onClick = { cloud = true }, label = { Text(tr(R.string.s226)) })
                }
                if (cloud) {
                    Text(tr(R.string.s227),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = groqKey, onValueChange = { groqKey = it.trim() }, singleLine = true, enabled = !working,
                        label = { Text(tr(R.string.s228)) }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    Row {
                        TextButton(enabled = !working, onClick = {
                            try {
                                context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(GroqWhisper.KEYS_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (_: Exception) { status = tr(R.string.s229, GroqWhisper.KEYS_URL) }
                        }) { Text(tr(R.string.s230)) }
                        if (groqKey.isNotBlank()) TextButton(enabled = !working, onClick = {
                            groqKey = ""; SecretStore.set(context, GroqWhisper.KEY_NAME, null)
                        }) { Text(tr(R.string.s231)) }
                    }
                } else {
                Text(tr(R.string.s232),
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Text(tr(R.string.s233), style = MaterialTheme.typography.labelLarge)
                models.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !working) { model = m }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = model == m, onClick = null, enabled = !working)
                        Spacer(Modifier.width(8.dp))
                        Text("${m.label}  ${m.sizeMb} MB", modifier = Modifier.weight(1f))
                        if (m in downloaded) TextButton(enabled = !working, onClick = {
                            LocalWhisper.delete(context, m); downloaded = downloaded - m
                        }) { Text(tr(R.string.s047)) } else Text(tr(R.string.s234), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (model == LocalWhisper.Model.Turbo) {
                    Text(tr(R.string.s235),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr(R.string.s237, AI_LANGS.firstOrNull { it.first == lang }?.second ?: tr(R.string.s236)), style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f))
                    TextButton(enabled = !working, onClick = { pickLang = !pickLang }) { Text(if (pickLang) tr(R.string.s238) else tr(R.string.s239)) }
                }
                if (pickLang) {
                    Text(tr(R.string.s240), style = MaterialTheme.typography.bodySmall)
                    FlowRow(Modifier.padding(vertical = 4.dp)) {
                        AI_LANGS.forEach { (code, label) ->
                            FilterChip(selected = lang == code, enabled = !working, onClick = { lang = code }, label = { Text(label) },
                                modifier = Modifier.padding(end = 4.dp))
                        }
                    }
                }
                Text(tr(R.string.s241), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!cloud) Row(Modifier.fillMaxWidth().clickable(enabled = !working) { skipSilence = !skipSilence }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr(R.string.s242))
                        Text(tr(R.string.s243), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = skipSilence, onCheckedChange = null, enabled = !working)
                }
                if (!cloud) accelerated?.let {
                    Text(if (it) tr(R.string.s244) else tr(R.string.s245),
                        style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(4.dp))
                progress?.let { LinearProgressIndicator(progress = { it.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                val p = progress
                if (working && startedAt > 0 && p != null) {
                    val elapsed = System.currentTimeMillis() - startedAt
                    val pct = (p * 100).toInt().coerceIn(0, 100)
                    Text(if (p < 0.02f || elapsed < 15_000) tr(R.string.s246, pct) else tr(R.string.s247, pct, formatEta(((elapsed / p) * (1 - p)).toLong())),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (working && progress == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (working) TextButton(onClick = { LocalWhisper.cancel(); job?.cancel() }) { Text(tr(R.string.s248)) }
            else if (cloud) TextButton(enabled = groqKey.isNotBlank(), onClick = {
                SecretStore.set(context, GroqWhisper.KEY_NAME, groqKey)
                withFolder {
                    launchTask {
                        status = tr(R.string.s249)
                        progress = 0f
                        startedAt = System.currentTimeMillis()
                        val r = try {
                            GroqWhisper.transcribe(context, video, groqKey, lang.ifBlank { null },
                                onProgress = { progress = it },
                                onStatus = { status = it ?: tr(R.string.s249) })
                        } catch (e: GroqWhisper.InvalidKey) {
                            status = tr(R.string.s250); null
                        } finally { startedAt = 0L }
                        if (r != null) finish(r, "Groq", "Groq 字幕")
                    }
                }
            }) { Text(tr(R.string.s251)) }
            else if (model !in downloaded) TextButton(onClick = {
                launchTask {
                    status = tr(R.string.s252)
                    LocalWhisper.download(context, model) { progress = it }
                    downloaded = downloaded + model
                    status = tr(R.string.s253)
                }
            }) { Text(tr(R.string.s254, model.sizeMb)) }
            else TextButton(onClick = {
                withFolder {
                    launchTask {
                        status = tr(R.string.s255)
                        progress = 0f
                        startedAt = System.currentTimeMillis()
                        val r = try {
                            LocalWhisper.transcribe(context, video, model, lang.ifBlank { null }, skipSilence) { progress = it }
                        } finally { startedAt = 0L }
                        finish(r, "AI", "AI 字幕")
                    }
                }
            }) { Text(tr(R.string.s251)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text(tr(R.string.s002)) } },
    )
}

private fun formatEta(ms: Long): String {
    val min = (ms / 60_000).coerceAtLeast(1)
    return if (min < 60) tr(R.string.s103, min) else tr(R.string.s256, min / 60, min % 60)
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
        title = { Text(tr(R.string.s186)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr(R.string.s257), style = MaterialTheme.typography.bodySmall)
                if (osSignedIn) {
                    Row {
                        Text(tr(R.string.s258, osUser), modifier = Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = {
                            SecretStore.set(context, KEY_OS_TOKEN, null); osSignedIn = false
                        }) { Text(tr(R.string.s259)) }
                    }
                } else {
                    OutlinedTextField(value = osUser, onValueChange = { osUser = it }, singleLine = true,
                        label = { Text(tr(R.string.s260)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = osPass, onValueChange = { osPass = it }, singleLine = true,
                        label = { Text(tr(R.string.s261)) }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !working && OpenSubtitles.available && osUser.isNotBlank() && osPass.isNotBlank(), onClick = {
                        working = true
                        scope.launch {
                            try {
                                val token = OpenSubtitles.login(osUser, osPass)
                                SecretStore.set(context, KEY_OS_TOKEN, token)
                                SecretStore.set(context, KEY_OS_USER, osUser)
                                osPass = ""; osSignedIn = true; status = tr(R.string.s262)
                            } catch (e: Exception) { status = tr(R.string.s263, e.message) }
                            working = false
                        }
                    }) { Text(tr(R.string.s264)) }
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s265)) } },
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
    var status by remember { mutableStateOf<String?>(if (source == null) tr(R.string.s266) else null) }
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
                status = if (useGroq) tr(R.string.s267) else tr(R.string.s268)
                progress = 0f
                val out = if (useGroq) SubtitleTranslator.translateWithGroq(context, src, target, groqKey,
                    onProgress = { progress = it }, onStatus = { status = it ?: tr(R.string.s267) })
                else SubtitleTranslator.translateWithMlKit(context, src, target,
                    onProgress = { progress = it }, onStatus = { status = it ?: tr(R.string.s268) })
                val tag = if (useGroq) "Groq翻譯" else "MLKit翻譯"
                val sideName = SidecarSubs.fileName(video, target.code, tag)
                val saved = SidecarSubs.save(context, video, out, sideName)
                android.widget.Toast.makeText(context, saved?.let { tr(R.string.s269, it) } ?: tr(R.string.s270),
                    android.widget.Toast.LENGTH_LONG).show()
                onLoaded(out, tr(R.string.s272, target.label, if (useGroq) "Groq" else tr(R.string.s271)), sideName.takeIf { saved != null })
                onDismiss()
            } catch (e: CancellationException) { status = tr(R.string.s220) }
            catch (e: GroqWhisper.InvalidKey) { status = tr(R.string.s250) }
            catch (e: Exception) { status = tr(R.string.s222, e.message) }
            progress = null
            job = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(tr(R.string.s179)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.padding(bottom = 4.dp)) {
                    FilterChip(selected = useGroq, enabled = !working, onClick = { useGroq = true }, label = { Text(tr(R.string.s273)) },
                        modifier = Modifier.padding(end = 4.dp))
                    FilterChip(selected = !useGroq, enabled = !working, onClick = { useGroq = false }, label = { Text(tr(R.string.s274)) })
                }
                if (useGroq) {
                    Text(tr(R.string.s275),
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = groqKey, onValueChange = { groqKey = it.trim() }, singleLine = true, enabled = !working,
                        label = { Text(tr(R.string.s228)) }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(enabled = !working, onClick = {
                        runCatching {
                            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(GroqWhisper.KEYS_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }) { Text(tr(R.string.s230)) }
                } else {
                    Text(tr(R.string.s276),
                        style = MaterialTheme.typography.bodySmall)
                }
                Text(tr(R.string.s277), style = MaterialTheme.typography.labelLarge)
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
            if (working) TextButton(onClick = { job?.cancel() }) { Text(tr(R.string.s248)) }
            else TextButton(enabled = source != null && (!useGroq || groqKey.isNotBlank()), onClick = {
                if (SidecarSubs.needsGrant(context, video)) {
                    pendingStart = { start() }
                    status = tr(R.string.s278)
                    runCatching { folderPicker.launch(SidecarSubs.initialUri(video)) }.onFailure { pendingStart = null; start() }
                } else start()
            }) { Text(tr(R.string.s251)) }
        },
        dismissButton = { TextButton(enabled = !working, onClick = onDismiss) { Text(tr(R.string.s002)) } },
    )
}


private val AI_LANGS = listOf(
    "" to tr(R.string.s236), "zh" to tr(R.string.s279), "yue" to tr(R.string.s280), "en" to tr(R.string.s018), "ja" to tr(R.string.s019),
    "ko" to tr(R.string.s020), "th" to tr(R.string.s281), "vi" to tr(R.string.s282), "es" to tr(R.string.s283),
)
