package com.weinet.newvideoplayer.ui.vault

import com.weinet.newvideoplayer.tr
import com.weinet.newvideoplayer.R

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weinet.newvideoplayer.ui.library.Busy
import com.weinet.newvideoplayer.vault.PinStore
import com.weinet.newvideoplayer.vault.VaultEntry
import com.weinet.newvideoplayer.vault.VaultSession
import com.weinet.newvideoplayer.vault.VaultStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class VaultViewModel(private val app: Application) : AndroidViewModel(app) {
    val pins = PinStore(app)
    val entries = VaultStore.entries
    val busy = MutableStateFlow<Busy?>(null)
    val message = MutableStateFlow<String?>(null)
    val loadError = MutableStateFlow(false)
    val hasPin = MutableStateFlow(pins.hasPin)

    private var failures = 0
    val lockedUntil = MutableStateFlow(0L)

    fun setPin(pin: String) {
        pins.setPin(pin)
        hasPin.value = true
        unlock()
    }

    /** Returns true when the PIN is right. Five wrong tries lock input for 30 seconds. */
    fun tryPin(pin: String): Boolean {
        if (System.currentTimeMillis() < lockedUntil.value) return false
        return if (pins.check(pin)) { failures = 0; unlock(); true }
        else {
            if (++failures >= 5) { failures = 0; lockedUntil.value = System.currentTimeMillis() + 30_000 }
            false
        }
    }

    fun unlock() {
        VaultSession.unlocked.value = true
        viewModelScope.launch {
            try { VaultStore.load(app); loadError.value = false } catch (e: Exception) { loadError.value = true }
        }
    }

    fun restore(e: VaultEntry) = viewModelScope.launch {
        busy.value = Busy(tr(R.string.s402), 0f)
        val ok = VaultStore.restore(app, e) { p -> busy.value = Busy(tr(R.string.s402), p) }
        busy.value = null
        message.value = if (ok) tr(R.string.s403) else tr(R.string.s404)
    }

    fun delete(e: VaultEntry) { VaultStore.delete(app, e); message.value = tr(R.string.s405) }
    fun rename(e: VaultEntry, name: String) { VaultStore.rename(app, e, name); message.value = tr(R.string.s076) }
}
