package com.weinet.newvideoplayer

import com.weinet.newvideoplayer.vault.VaultCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Random
import javax.crypto.spec.SecretKeySpec

class CtrTest {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val nonce = ByteArray(8) { (it * 7).toByte() }

    @Test
    fun randomAccessDecryptMatchesStreamEncrypt() {
        val plain = ByteArray(200_003).also { Random(1).nextBytes(it) }
        val enc = plain.copyOf()
        // Encrypt in odd-sized chunks, like a stream.
        val ctr = VaultCrypto.Ctr(key, nonce)
        var pos = 0
        while (pos < enc.size) {
            val n = minOf(7_777, enc.size - pos)
            ctr.process(enc, pos, n, pos.toLong())
            pos += n
        }
        assertFalse(enc.contentEquals(plain))
        // Decrypt arbitrary slices, as a seek would.
        for ((start, len) in listOf(0 to 16, 5 to 100, 15 to 1, 99_999 to 50_000, 199_990 to 13)) {
            val slice = enc.copyOfRange(start, start + len)
            VaultCrypto.Ctr(key, nonce).process(slice, 0, len, start.toLong())
            assertArrayEquals(plain.copyOfRange(start, start + len), slice)
        }
    }
}
