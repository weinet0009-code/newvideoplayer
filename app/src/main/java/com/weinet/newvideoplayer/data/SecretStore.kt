package com.weinet.newvideoplayer.data

import android.content.Context
import com.weinet.newvideoplayer.vault.VaultCrypto
import java.io.File

/** Small secrets (API keys, tokens) kept encrypted with the app's Keystore-protected key. */
object SecretStore {
    private fun file(context: Context, name: String) = File(File(context.filesDir, "secrets").apply { mkdirs() }, "$name.nvp")

    fun get(context: Context, name: String): String? {
        val f = file(context, name)
        return if (!f.exists()) null else try { String(VaultCrypto.decryptBytes(context, f)) } catch (e: Exception) { null }
    }

    fun set(context: Context, name: String, value: String?) {
        val f = file(context, name)
        if (value.isNullOrBlank()) f.delete() else VaultCrypto.encryptBytes(context, value.toByteArray(), f)
    }
}
