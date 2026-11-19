package com.running.tenant

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM for secrets stored in a tenant database (design §7). Output: `enc:v1:` + base64(iv + ciphertext). */
class SecretCipher(masterKey: String) {
    private val key: SecretKeySpec
    private val random = SecureRandom()

    init {
        val bytes = try {
            Base64.getDecoder().decode(masterKey.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("APP_TENANT_SECRET_KEY must be base64 of exactly 32 bytes")
        }
        check(bytes.size == KEY_BYTES) { "APP_TENANT_SECRET_KEY must be base64 of exactly 32 bytes" }
        key = SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
    }

    /** Null when the text is not ours, was tampered with, or was written with another master key. */
    fun decrypt(stored: String): String? {
        if (!stored.startsWith(PREFIX)) return null
        return try {
            val raw = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
            if (raw.size <= IV_BYTES) return null
            val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, raw.copyOfRange(0, IV_BYTES))) }
            String(cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    override fun toString() = "SecretCipher"

    companion object {
        const val PREFIX = "enc:v1:"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val KEY_BYTES = 32
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
