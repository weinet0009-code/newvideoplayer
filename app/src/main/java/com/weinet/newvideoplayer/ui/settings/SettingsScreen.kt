package com.weinet.newvideoplayer.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.weinet.newvideoplayer.BuildConfig
import com.weinet.newvideoplayer.data.LibraryStore
import com.weinet.newvideoplayer.data.PlayerSettings
import com.weinet.newvideoplayer.data.SecretStore
import com.weinet.newvideoplayer.tools.GroqWhisper
import com.weinet.newvideoplayer.tools.LocalWhisper
import com.weinet.newvideoplayer.ui.player.ApiSettingsDialog
import com.weinet.newvideoplayer.ui.theme.ThemeDialog
import com.weinet.newvideoplayer.ui.theme.ThemeMode
import com.weinet.newvideoplayer.ui.theme.ThemeStore
import java.io.File

/** Pages opened from the settings list (shown in place of it, with a back arrow). */
enum class SettingsPage(val title: String) { Vault("私密資料夾"), Licenses("開源授權"), Privacy("隱私說明") }

const val REPO_URL = "https://github.com/weinet0009-code/newvideoplayer"

private fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

@Composable
fun SettingsList(onPage: (SettingsPage) -> Unit) {
    val context = LocalContext.current
    val settings = remember { PlayerSettings(context) }
    val theme by ThemeStore.state.collectAsState()
    var sw by remember { mutableStateOf(settings.preferSoftwareDecoder) }
    var bg by remember { mutableStateOf(settings.backgroundPlay) }
    var pip by remember { mutableStateOf(settings.autoPip) }
    var dialog by remember { mutableStateOf<String?>(null) }

    @Composable
    fun Header(text: String) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp))
    }

    @Composable
    fun Item(icon: ImageVector, title: String, sub: String? = null, onClick: () -> Unit) {
        ListItem(
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(title) },
            supportingContent = sub?.let { { Text(it) } },
            modifier = Modifier.clickable(onClick = onClick),
        )
    }

    @Composable
    fun Toggle(icon: ImageVector, title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
        ListItem(
            leadingContent = { Icon(icon, null) },
            headlineContent = { Text(title) },
            supportingContent = { Text(sub) },
            trailingContent = { Switch(checked = value, onCheckedChange = null) },
            modifier = Modifier.clickable { onChange(!value) },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Header("外觀")
        Item(Icons.Default.Palette, "佈景主題", when (theme.mode) {
            ThemeMode.Auto -> "自動（跟隨手機設定）"
            ThemeMode.Fixed -> "配色：${theme.palette.label}"
            ThemeMode.Custom -> "自由配色"
            ThemeMode.Image -> "自選圖片"
        }) { dialog = "theme" }

        Header("播放")
        Toggle(Icons.Default.Memory, "軟體解碼", "支援更多格式（如 AVI），比較耗電", sw) { sw = it; settings.preferSoftwareDecoder = it }
        Toggle(Icons.Default.Headphones, "背景播放", "離開 app 後繼續播放聲音", bg) { bg = it; settings.backgroundPlay = it }
        Toggle(Icons.Default.PictureInPictureAlt, "自動子母畫面", "播放中按 Home 鍵時縮成小視窗", pip) { pip = it; settings.autoPip = it }

        Header("字幕與 AI")
        Item(Icons.Default.Key, "Groq 金鑰", if (SecretStore.get(context, GroqWhisper.KEY_NAME) != null) "已設定" else "未設定（雲端 AI 字幕與翻譯需要）") { dialog = "groq" }
        Item(Icons.Default.Subtitles, "OpenSubtitles 帳號", "選填，登入可提高每日下載數") { dialog = "os" }
        Item(Icons.Default.Download, "本機 AI 模型", "查看大小、刪除以釋放空間") { dialog = "models" }
        Item(Icons.Default.FolderOpen, "已授權寫入的資料夾", "字幕存到影片旁邊時授權的資料夾") { dialog = "grants" }

        Header("隱私")
        Item(Icons.Default.Lock, "私密資料夾", "加密存放的影片，需 PIN 或指紋") { onPage(SettingsPage.Vault) }
        Item(Icons.Default.History, "清除最近播放") { dialog = "recent" }

        Header("關於")
        Item(Icons.Default.Info, "軟體版本", "${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）") {}
        Item(Icons.Default.Description, "開源授權") { onPage(SettingsPage.Licenses) }
        Item(Icons.Default.PrivacyTip, "隱私說明", "哪些功能會連網、傳送什麼") { onPage(SettingsPage.Privacy) }
        Item(Icons.Default.Code, "GitHub 專案頁面") { openUrl(context, REPO_URL) }
        Spacer(Modifier.height(24.dp))
    }

    when (dialog) {
        "theme" -> ThemeDialog(onDismiss = { dialog = null })
        "groq" -> GroqKeyDialog(onDismiss = { dialog = null })
        "os" -> ApiSettingsDialog(onDismiss = { dialog = null })
        "models" -> ModelsDialog(onDismiss = { dialog = null })
        "grants" -> GrantsDialog(onDismiss = { dialog = null })
        "recent" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("清除最近播放？") },
            text = { Text("只清除紀錄，影片不會被刪除。") },
            confirmButton = { TextButton(onClick = { LibraryStore.clearRecent(); dialog = null }) { Text("清除") } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun GroqKeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(SecretStore.get(context, GroqWhisper.KEY_NAME) != null) }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Groq 金鑰") },
        text = {
            Column {
                Text("免費申請，用於雲端 AI 字幕與 Groq 翻譯。金鑰只存在這支手機，加密保存。", style = MaterialTheme.typography.bodySmall)
                if (saved) {
                    Row {
                        Text("已設定", Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { SecretStore.set(context, GroqWhisper.KEY_NAME, null); saved = false }) { Text("清除") }
                    }
                } else {
                    OutlinedTextField(value = key, onValueChange = { key = it.trim() }, singleLine = true,
                        label = { Text("貼上金鑰（gsk_…）") }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { openUrl(context, GroqWhisper.KEYS_URL) }) { Text("到 Groq 申請金鑰") }
                }
            }
        },
        confirmButton = {
            if (!saved) TextButton(enabled = key.isNotBlank(), onClick = {
                SecretStore.set(context, GroqWhisper.KEY_NAME, key); onDismiss()
            }) { Text("儲存") } else TextButton(onClick = onDismiss) { Text("完成") }
        },
        dismissButton = { if (!saved) TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun mb(f: File) = "%.0f MB".format(f.length() / 1_048_576.0)

@Composable
private fun ModelsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val models = remember(tick) { LocalWhisper.Model.entries.filter { LocalWhisper.isDownloaded(context, it) } }
    val vad = remember(tick) { LocalWhisper.vadFile(context).takeIf { it.exists() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("本機 AI 模型") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (models.isEmpty() && vad == null) Text("還沒有下載任何模型。")
                models.forEach { m ->
                    ListItem(
                        headlineContent = { Text(m.label) },
                        supportingContent = { Text(mb(LocalWhisper.modelFile(context, m))) },
                        trailingContent = {
                            IconButton(onClick = { LocalWhisper.delete(context, m); tick++ }) { Icon(Icons.Default.Delete, "刪除") }
                        },
                    )
                }
                vad?.let { f ->
                    ListItem(
                        headlineContent = { Text("靜音偵測（VAD）") },
                        supportingContent = { Text(mb(f)) },
                        trailingContent = { IconButton(onClick = { f.delete(); tick++ }) { Icon(Icons.Default.Delete, "刪除") } },
                    )
                }
                Text("刪除後下次使用會重新下載。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

@Composable
private fun GrantsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val grants = remember(tick) { context.contentResolver.persistedUriPermissions.filter { it.isWritePermission } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("已授權寫入的資料夾") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (grants.isEmpty()) Text("目前沒有授權任何資料夾。")
                grants.forEach { g ->
                    val name = Uri.decode(g.uri.lastPathSegment ?: g.uri.toString()).removePrefix("primary:").ifEmpty { "內部儲存空間" }
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = {
                            TextButton(onClick = {
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(g.uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                tick++
                            }) { Text("取消授權") }
                        },
                    )
                }
                Text("取消後，下次在該資料夾存字幕會再詢問一次。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private val LICENSES = listOf(
    "FFmpeg 7.1" to "LGPL 2.1 以上。本 app 以 LGPL 設定自行編譯（未啟用 GPL／nonfree），原始碼：https://ffmpeg.org ，編譯腳本見專案 ffmpeg/build.sh。",
    "dav1d" to "BSD 2-Clause，VideoLAN。",
    "LAME 3.100" to "LGPL 2.0 以上，用於 MP3 匯出。原始碼：https://lame.sourceforge.io",
    "whisper.cpp / ggml" to "MIT，Georgi Gerganov 與貢獻者。",
    "AndroidX Media3 (ExoPlayer)" to "Apache 2.0，Google。部分 FFmpeg 擴充檔改編自 Media3，檔頭保留原聲明。",
    "Jetpack Compose、AndroidX" to "Apache 2.0，Google。",
    "Coil" to "Apache 2.0，Coil Contributors。",
    "ML Kit 翻譯與語言辨識" to "依 Google ML Kit 服務條款使用。",
    "Whisper 模型" to "MIT，OpenAI；ggml 格式由 whisper.cpp 專案提供。",
)

@Composable
fun LicensesPage() {
    SelectionContainer {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            LICENSES.forEach { (name, text) ->
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
            }
        }
    }
}

@Composable
fun PrivacyPage() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        listOf(
            "本機處理" to "播放、本機 AI 字幕、ML Kit 離線翻譯、MP3 轉檔、私密資料夾都只在手機上處理，不上傳。",
            "雲端 AI 字幕（Groq）" to "選擇雲端時，影片的聲音會切段上傳到 Groq 辨識。使用你自己的金鑰。",
            "Groq 翻譯" to "字幕文字會傳到 Groq 翻譯。",
            "線上搜尋字幕" to "影片名稱會傳到 OpenSubtitles 搜尋。",
            "模型下載" to "本機 AI 模型從 Hugging Face 下載，ML Kit 語言包從 Google 下載。",
            "投放到電視" to "影片只在同一個 Wi‑Fi 內傳到你的 Chromecast。",
            "金鑰與帳號" to "Groq 金鑰與 OpenSubtitles 登入資訊加密存在手機，不會寫進 app 或傳給開發者。",
            "私密資料夾" to "影片以 AES-256 加密存在 app 內，金鑰由手機的安全硬體保護。解除安裝 app 會一併刪除。",
        ).forEach { (h, t) ->
            Text(h, style = MaterialTheme.typography.titleSmall)
            Text(t, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}
