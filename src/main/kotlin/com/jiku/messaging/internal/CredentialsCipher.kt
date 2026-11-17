package com.jiku.messaging.internal

import com.jiku.shared.AesGcmCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Encrypts tenant provider credentials at rest (JIKU-44) with
 * PROVIDER_CREDENTIALS_ENCRYPTION_KEY. Rotating the secret invalidates stored
 * credentials (tenants re-enter them), the accepted trade-off at this stage.
 */
@Component
class CredentialsCipher(
    @Value("\${jiku.provider-credentials.encryption-key}") secret: String,
) {
    private val cipher = AesGcmCipher(secret, "jiku.provider-credentials.encryption-key")

    fun encrypt(plaintext: String): String = cipher.encrypt(plaintext)

    fun decrypt(encoded: String): String = cipher.decrypt(encoded)
}
