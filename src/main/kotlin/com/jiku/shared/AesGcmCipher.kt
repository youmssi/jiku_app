package com.jiku.shared

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts short secrets at rest: AES-256-GCM with a random 12-byte IV prepended
 * to the ciphertext, base64-encoded for storage. The key is derived (SHA-256)
 * from a configured secret of at least 32 characters, so any long random value
 * works without base64 ceremony. Rotating the secret makes stored values
 * unreadable, which each owner of a cipher accepts for what it stores.
 */
class AesGcmCipher(
    secret: String,
    settingName: String,
) {
    private val key: SecretKeySpec
    private val random = SecureRandom()

    init {
        check(secret.length >= MIN_SECRET_LENGTH) { "$settingName must be at least $MIN_SECRET_LENGTH characters" }
        key = SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8)), "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_LENGTH).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(encoded: String): String {
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size > IV_LENGTH) { "Encrypted payload is too short" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_LENGTH))
        return String(cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
        const val MIN_SECRET_LENGTH = 32
    }
}
