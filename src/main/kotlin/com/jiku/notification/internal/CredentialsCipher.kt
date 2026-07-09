package com.jiku.notification.internal

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts tenant provider credentials at rest (JIKU-44). AES-256-GCM with a
 * random 12-byte IV prepended to the ciphertext, base64-encoded for storage.
 * The key is derived (SHA-256) from PROVIDER_CREDENTIALS_ENCRYPTION_KEY so any
 * sufficiently long secret works without base64 ceremony; rotating the secret
 * invalidates stored credentials (tenants re-enter them), which is the accepted
 * trade-off at this stage — no key-versioning machinery yet.
 */
@Component
class CredentialsCipher(
    @Value("\${jiku.provider-credentials.encryption-key}") secret: String,
) {
    private val key: SecretKeySpec
    private val random = SecureRandom()

    init {
        check(secret.length >= MIN_SECRET_LENGTH) {
            "jiku.provider-credentials.encryption-key must be at least $MIN_SECRET_LENGTH characters"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
        key = SecretKeySpec(digest, "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ciphertext)
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > IV_LENGTH) { "Encrypted payload is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            key,
            GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH),
        )
        val plaintext = cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
        return String(plaintext, Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val MIN_SECRET_LENGTH = 32
    }
}
