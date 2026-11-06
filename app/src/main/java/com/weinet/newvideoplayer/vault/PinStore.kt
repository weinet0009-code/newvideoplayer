package com.weinet.newvideoplayer.vault

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PIN for the private folder, stored only as a salted PBKDF2 hash. */
class PinStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vault_pin", Context.MODE_PRIVATE)

    val hasPin: Boolean get() = prefs.contains("hash")

    var biometricEnabled: Boolean
        get() = prefs.getBoolean("biometric", true)
        set(v) = prefs.edit().putBoolean("biometric", v).apply()

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs.edit()
            .putString("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("hash", Base64.encodeToString(hash(pin, salt), Base64.NO_WRAP))
            .apply()
    }

    fun check(pin: String): Boolean {
        val salt = Base64.decode(prefs.getString("salt", null) ?: return false, Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("hash", null) ?: return false, Base64.NO_WRAP)
        return MessageDigest.isEqual(hash(pin, salt), expected)
    }

    private fun hash(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)).encoded
}

/** Whether the private folder is open right now. Locks when the app leaves the screen. */
object VaultSession {
    val unlocked = MutableStateFlow(false)
    fun lock() { unlocked.value = false }
}
