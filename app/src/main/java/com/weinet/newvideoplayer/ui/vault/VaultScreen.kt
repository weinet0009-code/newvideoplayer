package com.weinet.newvideoplayer.ui.vault

import com.weinet.newvideoplayer.tr
import com.weinet.newvideoplayer.R

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
            title = if (changingPin) tr(R.string.s379) else tr(R.string.s380),
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
        Text(tr(R.string.s381), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        PinField(first, { first = it }, "PIN")
        Spacer(Modifier.height(8.dp))
        PinField(second, { second = it }, tr(R.string.s382))
        if (mismatch) Text(tr(R.string.s383), color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Row {
            onCancel?.let { TextButton(onClick = it) { Text(tr(R.string.s056)) } }
            Button(enabled = first.length >= 4 && first == second, onClick = { onDone(first) }) { Text(tr(R.string.s104)) }
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
                .setTitle(tr(R.string.s384))
                .setNegativeButtonText(tr(R.string.s385))
                .setAllowedAuthenticators(BIOMETRIC_WEAK)
                .build()
        )
    }
    LaunchedEffect(Unit) { if (canBio) prompt() }

    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.Lock, null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(tr(R.string.s386), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        PinField(pin, { pin = it; error = null }, "PIN")
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (canBio) IconButton(onClick = { prompt() }) { Icon(Icons.Default.Fingerprint, tr(R.string.s387)) }
            Button(enabled = pin.length >= 4, onClick = {
                if (!vm.tryPin(pin)) {
                    pin = ""
                    error = if (System.currentTimeMillis() < vm.lockedUntil.value) tr(R.string.s388) else tr(R.string.s389)
                }
            }) { Text(tr(R.string.s157)) }
        }
        if (System.currentTimeMillis() < lockedUntil) Text(tr(R.string.s390), style = MaterialTheme.typography.bodySmall)
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
            Text(tr(R.string.s391, entries.size), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f).padding(start = 8.dp))
            TextButton(onClick = { bio = !bio; vm.pins.biometricEnabled = bio }) { Text(if (bio) tr(R.string.s392) else tr(R.string.s393)) }
            TextButton(onClick = onChangePin) { Text(tr(R.string.s394)) }
            TextButton(onClick = { VaultSession.lock() }) { Text(tr(R.string.s395)) }
        }
        when {
            loadError -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr(R.string.s396)) }
            entries.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(tr(R.string.s397),
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
            VideoAction(tr(R.string.s093), Icons.Default.PlayArrow) { onOpen(entries.map { it.toVideo() }, entries.indexOf(e)) },
            VideoAction(tr(R.string.s398), Icons.Default.LockOpen) { vm.restore(e) },
            VideoAction(tr(R.string.s045), Icons.Default.Edit) { renameFor = e },
            VideoAction(tr(R.string.s046), Icons.Default.Info) { detailsFor = e },
            VideoAction(tr(R.string.s399), Icons.Default.DeleteForever) { deleteFor = e },
        ), onDismiss = { sheetFor = null })
    }
    renameFor?.let { e -> RenameDialog(e.name, onDismiss = { renameFor = null }, onRename = { vm.rename(e, it) }) }
    deleteFor?.let { e ->
        ConfirmDialog(tr(R.string.s400), tr(R.string.s401, e.name), tr(R.string.s047),
            onDismiss = { deleteFor = null }, onConfirm = { vm.delete(e) })
    }
    detailsFor?.let { e -> DetailsDialog(context, e.toVideo(), onDismiss = { detailsFor = null }) }
    busy?.let { BusyDialog(it.label, it.progress) }
    message?.let { m ->
        LaunchedEffect(m) { android.widget.Toast.makeText(context, m, android.widget.Toast.LENGTH_SHORT).show(); vm.message.value = null }
    }
}
