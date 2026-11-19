package com.running.tenant

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class SecretCipherTest {
    private fun key(fill: Int) = Base64.getEncoder().encodeToString(ByteArray(32) { fill.toByte() })
    private val cipher = SecretCipher(key(7))

    @Test
    fun `round trip with a random iv and a prefix`() {
        val a = cipher.encrypt("fake-garmin-key-0001")
        val b = cipher.encrypt("fake-garmin-key-0001")
        assertTrue(a.startsWith("enc:v1:"))
        assertFalse(a.contains("fake-garmin-key"))
        assertNotEquals(a, b)
        assertEquals("fake-garmin-key-0001", cipher.decrypt(a))
        assertEquals("fake-garmin-key-0001", cipher.decrypt(b))
    }

    @Test
    fun `wrong master key, tampering and plaintext give null, never an exception or the text`() {
        val stored = cipher.encrypt("fake-garmin-key-0001")
        assertNull(SecretCipher(key(8)).decrypt(stored))
        val raw = Base64.getDecoder().decode(stored.removePrefix("enc:v1:"))
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        assertNull(cipher.decrypt("enc:v1:" + Base64.getEncoder().encodeToString(raw)))
        assertNull(cipher.decrypt("fake-garmin-key-0001"))
        assertNull(cipher.decrypt("enc:v1:%%%"))
        assertNull(cipher.decrypt("enc:v1:AAAA"))
    }

    @Test
    fun `a malformed master key is refused without echoing it`() {
        val bad = "not-base64-at-all!"
        assertEquals(false, assertThrows<IllegalStateException> { SecretCipher(bad) }.message!!.contains(bad))
        assertThrows<IllegalStateException> { SecretCipher(Base64.getEncoder().encodeToString(ByteArray(16))) }
        assertThrows<IllegalStateException> { SecretCipher("") }
        assertEquals("SecretCipher", cipher.toString())
    }
}
