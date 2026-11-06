package com.weinet.newvideoplayer.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Private-folder encryption.
 *
 * Files are AES-256 in CTR mode, so any byte can be decrypted on its own and playback can
 * seek. The data key is random and stored wrapped by a non-exportable Android Keystore key.
 * File layout: "NVP1" + 8-byte random nonce + ciphertext. Counter block = nonce || block index.
 */
object VaultCrypto {
    private val MAGIC = "NVP1".toByteArray()
    const val HEADER = 12
    private const val KEK_ALIAS = "nvp_vault_kek"
    private const val PREFS = "vault_key"

    @Volatile private var dataKey: SecretKeySpec? = null

    @Synchronized
    fun key(context: Context): SecretKeySpec {
        dataKey?.let { return it }
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kek = kek()
        val wrapped = prefs.getString("wrapped", null)
        val raw = if (wrapped != null) {
            val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, kek, GCMParameterSpec(128, iv))
                doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
            }
        } else {
            val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, kek) }
            val w = c.doFinal(fresh)
            prefs.edit()
                .putString("wrapped", Base64.encodeToString(w, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(c.iv, Base64.NO_WRAP))
                .commit()
            fresh
        }
        return SecretKeySpec(raw, "AES").also { dataKey = it }
    }

    private fun kek(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEK_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(KEK_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** Random-access AES-CTR keystream; XORs data in place. Not thread-safe. */
    class Ctr(key: SecretKeySpec, private val nonce: ByteArray) {
        private val ecb = Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }

        fun process(buf: ByteArray, off: Int, len: Int, streamPos: Long) {
            var done = 0
            while (done < len) {
                val pos = streamPos + done
                val block = pos / 16
                val inBlock = (pos % 16).toInt()
                val blocks = min((len - done + inBlock + 15) / 16, 4096)
                val ctr = ByteArray(blocks * 16)
                for (i in 0 until blocks) {
                    System.arraycopy(nonce, 0, ctr, i * 16, 8)
                    var v = block + i
                    for (b in 15 downTo 8) { ctr[i * 16 + b] = (v and 0xFF).toByte(); v = v ushr 8 }
                }
                val ks = ecb.doFinal(ctr)
                val n = min(len - done, blocks * 16 - inBlock)
                for (i in 0 until n) {
                    val idx = off + done + i
                    buf[idx] = (buf[idx].toInt() xor ks[inBlock + i].toInt()).toByte()
                }
                done += n
            }
        }
    }

    private fun newNonce() = ByteArray(8).also { SecureRandom().nextBytes(it) }

    /** Encrypts [input] into [out]; reports bytes written to [onProgress]. */
    fun encryptStream(context: Context, input: InputStream, out: File, onProgress: (Long) -> Unit = {}) {
        val nonce = newNonce()
        val ctr = Ctr(key(context), nonce)
        out.outputStream().buffered(1 shl 16).use { os ->
            os.write(MAGIC); os.write(nonce)
            val buf = ByteArray(1 shl 16)
            var pos = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                ctr.process(buf, 0, n, pos)
                os.write(buf, 0, n)
                pos += n
                onProgress(pos)
            }
        }
    }

    fun encryptBytes(context: Context, data: ByteArray, out: File) =
        encryptStream(context, data.inputStream(), out)

    fun decryptBytes(context: Context, file: File): ByteArray {
        val all = file.readBytes()
        require(all.size >= HEADER && all.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a vault file" }
        val body = all.copyOfRange(HEADER, all.size)
        Ctr(key(context), all.copyOfRange(4, HEADER)).process(body, 0, body.size, 0)
        return body
    }

    /** Opens an encrypted file for random-access reading of its plaintext. */
    class Reader(context: Context, file: File) : AutoCloseable {
        private val raf = RandomAccessFile(file, "r")
        private val ctr: Ctr
        val length: Long

        init {
            val header = ByteArray(HEADER)
            raf.readFully(header)
            require(header.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a vault file" }
            ctr = Ctr(key(context), header.copyOfRange(4, HEADER))
            length = raf.length() - HEADER
        }

        fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= length) return -1
            raf.seek(HEADER + pos)
            val n = raf.read(buf, off, min(len.toLong(), length - pos).toInt())
            if (n > 0) ctr.process(buf, off, n, pos)
            return n
        }

        override fun close() = raf.close()
    }
}
