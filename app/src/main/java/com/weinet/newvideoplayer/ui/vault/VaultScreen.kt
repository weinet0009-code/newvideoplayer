package com.weinet.newvideoplayer.ui.vault

import android.graphics.Bitmap
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.weinet.newvideoplayer.data.Video
import com.weinet.newvideoplayer.data.formatDuration
import com.weinet.newvideoplayer.ui.library.*
import com.weinet.newvideoplayer.vault.VaultEntry
import com.weinet.newvideoplayer.vault.VaultSession
import com.weinet.newvideoplayer.vault.VaultStore

@Composable
fun VaultScreen(onOpen: (List<Video>, Int) -> Unit, vm: VaultViewModel = viewModel()) {
    val unlocked by VaultSession.unlocked.collectAsStateWithLifecycle()
    val hasPin by vm.hasPin.collectAsStateWithLifecycle()
    var changingPin by remember { mutableStateOf(false) }
    when {
        !hasPin || changingPin -> SetPin(
            title = if (changingPin) "設定新的 PIN" else "設定私密資料夾 PIN",
            onDone = { vm.setPin(it); changingPin = false },
            onCancel = if (changingPin) ({ changingPin = false }) else null,
        )
        !unlocked -> Unlock(vm)
        else -> VaultList(vm, onOpen, onChangePin = { changingPin = true })
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> if (v.length <= 8 && v.all { it.isDigit() }) onChange(v) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        modifier = Modifier.width(240.dp),
    )
}

@Composable
private fun SetPin(title: String, onDone: (String) -> Unit, onCancel: (() -> Unit)?) {
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    val mismatch = second.length >= 4 && first != second
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("4–8 位數字。忘記 PIN 將無法打開私密影片。", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        PinField(first, { first = it }, "PIN")
        Spacer(Modifier.height(8.dp))
        PinField(second, { second = it }, "再輸入一次")
        if (mismatch) Text("兩次輸入不一樣", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Row {
            onCancel?.let { TextButton(onClick = it) { Text("取消") } }
            Button(enabled = first.length >= 4 && first == second, onClick = { onDone(first) }) { Text("確定") }
        }
    }
}

@Composable
private fun Unlock(vm: VaultViewModel) {
    val context = LocalContext.current
    val activity = context as FragmentActivity
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val lockedUntil by vm.lockedUntil.collectAsStateWithLifecycle()
    val canBio = remember {
        vm.pins.biometricEnabled && BiometricManager.from(context).canAuthenticate(BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
    }
    fun prompt() {
        val p = BiometricPrompt(activity, ContextCompat.getMainExecutor(context), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { vm.unlock() }
        })
        p.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("解鎖私密資料夾")
                .setNegativeButtonText("使用 PIN")
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .build()
        )
    }
    LaunchedEffect(Unit) { if (canBio) prompt() }

    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("輸入 PIN 解鎖", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        PinField(pin, { pin = it; error = null }, "PIN")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canBio) IconButton(onClick = { prompt() }) { Icon(Icons.Default.Fingerprint, "指紋解鎖") }
            Button(enabled = pin.length >= 4, onClick = {
                if (!vm.tryPin(pin)) {
                    pin = ""
                    error = if (System.currentTimeMillis() < vm.lockedUntil.value) "錯誤太多次，請 30 秒後再試" else "PIN 不正確"
                }
            }) { Text("解鎖") }
        }
        if (System.currentTimeMillis() < lockedUntil) Text("錯誤太多次，請稍後再試", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VaultList(vm: VaultViewModel, onOpen: (List<Video>, Int) -> Unit, onChangePin: () -> Unit) {
    val context = LocalContext.current
    val entries by vm.entries.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val loadError by vm.loadError.collectAsStateWithLifecycle()
    var sheetFor by remember { mutableStateOf<VaultEntry?>(null) }
    var renameFor by remember { mutableStateOf<VaultEntry?>(null) }
    var deleteFor by remember { mutableStateOf<VaultEntry?>(null) }
    var detailsFor by remember { mutableStateOf<VaultEntry?>(null) }
    var bio by remember { mutableStateOf(vm.pins.biometricEnabled) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${entries.size} 部私密影片", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = { bio = !bio; vm.pins.biometricEnabled = bio }) { Text(if (bio) "指紋：開" else "指紋：關") }
            TextButton(onClick = onChangePin) { Text("更改 PIN") }
            TextButton(onClick = { VaultSession.lock() }) { Text("鎖定") }
        }
        when {
            loadError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("無法讀取私密資料夾") }
            entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text("在影片或資料夾上長按，選「移到私密資料夾」。影片會加密存放在 app 內，相簿和其他 app 都看不到。",
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
            else -> LazyColumn {
                items(entries.size, key = { entries[it].id }) { i ->
                    val e = entries[i]
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(
                                onClick = { onOpen(entries.map { it.toVideo() }, i) },
                                onLongClick = { sheetFor = e },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val thumb by produceState<Bitmap?>(null, e.id) { value = VaultStore.thumbnail(context, e) }
                        Box(Modifier.size(width = 128.dp, height = 72.dp).clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)) {
                            thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            Text(formatDuration(e.durationMs), style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    sheetFor?.let { e ->
        VideoActionSheet(e.toVideo(), listOf(
            VideoAction("播放", Icons.Default.PlayArrow) { onOpen(entries.map { it.toVideo() }, entries.indexOf(e)) },
            VideoAction("還原到相簿", Icons.Default.LockOpen) { vm.restore(e) },
            VideoAction("重新命名", Icons.Default.Edit) { renameFor = e },
            VideoAction("詳細資訊", Icons.Default.Info) { detailsFor = e },
            VideoAction("永久刪除", Icons.Default.DeleteForever) { deleteFor = e },
        ), onDismiss = { sheetFor = null })
    }
    renameFor?.let { e -> RenameDialog(e.name, onDismiss = { renameFor = null }, onRename = { vm.rename(e, it) }) }
    deleteFor?.let { e ->
        ConfirmDialog("永久刪除？", "「${e.name}」會被刪除，無法復原。", "刪除",
            onDismiss = { deleteFor = null }, onConfirm = { vm.delete(e) })
    }
    detailsFor?.let { e -> DetailsDialog(context, e.toVideo(), onDismiss = { detailsFor = null }) }
    busy?.let { BusyDialog(it.label, it.progress) }
    message?.let { m ->
        LaunchedEffect(m) { android.widget.Toast.makeText(context, m, android.widget.Toast.LENGTH_SHORT).show(); vm.message.value = null }
    }
}
