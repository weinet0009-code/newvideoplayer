package com.weinet.newvideoplayer.ui.settings

import com.weinet.newvideoplayer.tr
import com.weinet.newvideoplayer.R

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
import com.weinet.newvideoplayer.I18n
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
enum class SettingsPage(val title: String) { Vault(tr(R.string.s284)), Licenses(tr(R.string.s285)), Privacy(tr(R.string.s286)) }

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
        Header(tr(R.string.s287))
        Item(Icons.Default.Palette, tr(R.string.s288), when (theme.mode) {
            ThemeMode.Auto -> tr(R.string.s289)
            ThemeMode.Fixed -> tr(R.string.s290, theme.palette.label)
            ThemeMode.Custom -> tr(R.string.s291)
            ThemeMode.Image -> tr(R.string.s292)
        }) { dialog = "theme" }
        Item(Icons.Default.Language, tr(R.string.lang_title),
            I18n.chosenTag(context)?.let { t -> I18n.LANGUAGES.firstOrNull { it.first == t }?.second ?: t } ?: tr(R.string.lang_system)) { dialog = "lang" }

        Header(tr(R.string.s093))
        Toggle(Icons.Default.Memory, tr(R.string.s293), tr(R.string.s294), sw) { sw = it; settings.preferSoftwareDecoder = it }
        Toggle(Icons.Default.Headphones, tr(R.string.s188), tr(R.string.s295), bg) { bg = it; settings.backgroundPlay = it }
        Toggle(Icons.Default.PictureInPictureAlt, tr(R.string.s296), tr(R.string.s297), pip) { pip = it; settings.autoPip = it }

        Header(tr(R.string.s298))
        Item(Icons.Default.Key, tr(R.string.s299), if (SecretStore.get(context, GroqWhisper.KEY_NAME) != null) tr(R.string.s300) else tr(R.string.s301)) { dialog = "groq" }
        Item(Icons.Default.Subtitles, tr(R.string.s186), tr(R.string.s187)) { dialog = "os" }
        Item(Icons.Default.Download, tr(R.string.s302), tr(R.string.s303)) { dialog = "models" }
        Item(Icons.Default.FolderOpen, tr(R.string.s304), tr(R.string.s305)) { dialog = "grants" }

        Header(tr(R.string.s306))
        Item(Icons.Default.Lock, tr(R.string.s284), tr(R.string.s307)) { onPage(SettingsPage.Vault) }
        Item(Icons.Default.History, tr(R.string.s308)) { dialog = "recent" }

        Header(tr(R.string.s309))
        Item(Icons.Default.Info, tr(R.string.s310), tr(R.string.s194, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)) {}
        Item(Icons.Default.Description, tr(R.string.s285)) { onPage(SettingsPage.Licenses) }
        Item(Icons.Default.PrivacyTip, tr(R.string.s286), tr(R.string.s311)) { onPage(SettingsPage.Privacy) }
        Item(Icons.Default.Code, tr(R.string.s312)) { openUrl(context, REPO_URL) }
        Spacer(Modifier.height(24.dp))
    }

    when (dialog) {
        "theme" -> ThemeDialog(onDismiss = { dialog = null })
        "lang" -> LanguageDialog(onDismiss = { dialog = null })
        "groq" -> GroqKeyDialog(onDismiss = { dialog = null })
        "os" -> ApiSettingsDialog(onDismiss = { dialog = null })
        "models" -> ModelsDialog(onDismiss = { dialog = null })
        "grants" -> GrantsDialog(onDismiss = { dialog = null })
        "recent" -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(tr(R.string.s313)) },
            text = { Text(tr(R.string.s314)) },
            confirmButton = { TextButton(onClick = { LibraryStore.clearRecent(); dialog = null }) { Text(tr(R.string.s031)) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(tr(R.string.s056)) } },
        )
    }
}

@Composable
private fun LanguageDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = remember { I18n.chosenTag(context) }
    @Composable
    fun LangRow(tag: String?, name: String) {
        ListItem(
            leadingContent = { RadioButton(selected = current == tag, onClick = null) },
            headlineContent = { Text(name) },
            modifier = Modifier.clickable {
                onDismiss()
                if (tag != current) (context as? android.app.Activity)?.let { I18n.set(it, tag) }
            },
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.lang_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(tr(R.string.lang_restart), style = MaterialTheme.typography.bodySmall)
                LangRow(null, tr(R.string.lang_system))
                I18n.LANGUAGES.forEach { (t, n) -> LangRow(t, n) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s002)) } },
    )
}

@Composable
private fun GroqKeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var saved by remember { mutableStateOf(SecretStore.get(context, GroqWhisper.KEY_NAME) != null) }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s299)) },
        text = {
            Column {
                Text(tr(R.string.s315), style = MaterialTheme.typography.bodySmall)
                if (saved) {
                    Row {
                        Text(tr(R.string.s300), Modifier.weight(1f).padding(top = 12.dp))
                        TextButton(onClick = { SecretStore.set(context, GroqWhisper.KEY_NAME, null); saved = false }) { Text(tr(R.string.s031)) }
                    }
                } else {
                    OutlinedTextField(value = key, onValueChange = { key = it.trim() }, singleLine = true,
                        label = { Text(tr(R.string.s316)) }, visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { openUrl(context, GroqWhisper.KEYS_URL) }) { Text(tr(R.string.s317)) }
                }
            }
        },
        confirmButton = {
            if (!saved) TextButton(enabled = key.isNotBlank(), onClick = {
                SecretStore.set(context, GroqWhisper.KEY_NAME, key); onDismiss()
            }) { Text(tr(R.string.s318)) } else TextButton(onClick = onDismiss) { Text(tr(R.string.s265)) }
        },
        dismissButton = { if (!saved) TextButton(onClick = onDismiss) { Text(tr(R.string.s056)) } },
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
        title = { Text(tr(R.string.s302)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (models.isEmpty() && vad == null) Text(tr(R.string.s319))
                models.forEach { m ->
                    ListItem(
                        headlineContent = { Text(m.label) },
                        supportingContent = { Text(mb(LocalWhisper.modelFile(context, m))) },
                        trailingContent = {
                            IconButton(onClick = { LocalWhisper.delete(context, m); tick++ }) { Icon(Icons.Default.Delete, tr(R.string.s047)) }
                        },
                    )
                }
                vad?.let { f ->
                    ListItem(
                        headlineContent = { Text(tr(R.string.s320)) },
                        supportingContent = { Text(mb(f)) },
                        trailingContent = { IconButton(onClick = { f.delete(); tick++ }) { Icon(Icons.Default.Delete, tr(R.string.s047)) } },
                    )
                }
                Text(tr(R.string.s321), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s265)) } },
    )
}

@Composable
private fun GrantsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val grants = remember(tick) { context.contentResolver.persistedUriPermissions.filter { it.isWritePermission } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.s304)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (grants.isEmpty()) Text(tr(R.string.s322))
                grants.forEach { g ->
                    val name = Uri.decode(g.uri.lastPathSegment ?: g.uri.toString()).removePrefix("primary:").ifEmpty { tr(R.string.s323) }
                    ListItem(
                        headlineContent = { Text(name) },
                        trailingContent = {
                            TextButton(onClick = {
                                runCatching {
                                    context.contentResolver.releasePersistableUriPermission(g.uri,
                                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                                }
                                tick++
                            }) { Text(tr(R.string.s324)) }
                        },
                    )
                }
                Text(tr(R.string.s325), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr(R.string.s265)) } },
    )
}

private val LICENSES = listOf(
    "FFmpeg 7.1" to tr(R.string.s326),
    "dav1d" to tr(R.string.s327),
    "LAME 3.100" to tr(R.string.s328),
    "whisper.cpp / ggml" to tr(R.string.s329),
    "AndroidX Media3 (ExoPlayer)" to tr(R.string.s330),
    tr(R.string.s331) to tr(R.string.s332),
    "Coil" to tr(R.string.s333),
    tr(R.string.s334) to tr(R.string.s335),
    tr(R.string.s336) to tr(R.string.s337),
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
            tr(R.string.s338) to tr(R.string.s339),
            tr(R.string.s340) to tr(R.string.s341),
            tr(R.string.s342) to tr(R.string.s343),
            tr(R.string.s176) to tr(R.string.s344),
            tr(R.string.s345) to tr(R.string.s346),
            tr(R.string.s184) to tr(R.string.s347),
            tr(R.string.s348) to tr(R.string.s349),
            tr(R.string.s284) to tr(R.string.s350),
        ).forEach { (h, t) ->
            Text(h, style = MaterialTheme.typography.titleSmall)
            Text(t, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 12.dp))
        }
    }
}
