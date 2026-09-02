package com.jiku.messaging

import com.jiku.messaging.internal.CredentialsCipher
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CredentialsCipherTest {
    private val cipher = CredentialsCipher("unit-test-encryption-key-0123456789abcdef")

    @Test
    fun `roundtrips credentials and never stores them in clear`() {
        val plaintext = """{"apiKey":"re_secret_123","from":"no-reply@acme.test"}"""
        val encrypted = cipher.encrypt(plaintext)
        assertNotEquals(plaintext, encrypted)
        check(!encrypted.contains("re_secret_123"))
        assertEquals(plaintext, cipher.decrypt(encrypted))
    }

    @Test
    fun `each encryption uses a fresh iv`() {
        val plaintext = "same-input"
        assertNotEquals(cipher.encrypt(plaintext), cipher.encrypt(plaintext))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val encrypted = cipher.encrypt("payload")
        val tampered = encrypted.dropLast(4) + "AAA="
        assertFailsWith<Exception> { cipher.decrypt(tampered) }
    }

    @Test
    fun `a short encryption key is rejected at wiring time`() {
        assertFailsWith<IllegalStateException> { CredentialsCipher("too-short") }
    }
}
