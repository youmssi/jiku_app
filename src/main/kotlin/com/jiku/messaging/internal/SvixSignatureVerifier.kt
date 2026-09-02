package com.jiku.messaging.internal

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies Svix-style webhook signatures (the scheme Resend uses). The signed
 * content is `{svix-id}.{svix-timestamp}.{raw body}`, HMAC-SHA256 with the
 * base64-decoded secret (after its `whsec_` prefix), base64-encoded, and the
 * `svix-signature` header carries one or more space-separated `v1,<sig>`
 * candidates. A timestamp outside the tolerance window is rejected to prevent
 * replay of captured deliveries.
 */
object SvixSignatureVerifier {
    private const val SECRET_PREFIX = "whsec_"
    private const val VERSION_PREFIX = "v1,"
    private const val TOLERANCE_SECONDS = 300L

    fun verify(
        secret: String,
        svixId: String?,
        svixTimestamp: String?,
        svixSignature: String?,
        payload: String,
        now: Instant = Instant.now(),
    ): Boolean {
        if (svixId.isNullOrBlank() || svixTimestamp.isNullOrBlank() || svixSignature.isNullOrBlank()) {
            return false
        }
        val timestamp = svixTimestamp.toLongOrNull() ?: return false
        if (kotlin.math.abs(now.epochSecond - timestamp) > TOLERANCE_SECONDS) {
            return false
        }
        val key =
            try {
                Base64.getDecoder().decode(secret.removePrefix(SECRET_PREFIX))
            } catch (e: IllegalArgumentException) {
                return false
            }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val expected =
            Base64.getEncoder().encodeToString(
                mac.doFinal("$svixId.$svixTimestamp.$payload".toByteArray(Charsets.UTF_8)),
            )
        return svixSignature
            .split(" ")
            .asSequence()
            .filter { it.startsWith(VERSION_PREFIX) }
            .map { it.removePrefix(VERSION_PREFIX) }
            .any { candidate ->
                MessageDigest.isEqual(candidate.toByteArray(), expected.toByteArray())
            }
    }
}
