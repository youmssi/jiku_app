package com.jiku.mfa.internal

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Time-based one-time passwords (RFC 6238) as authentication apps compute them
 * by default: HMAC-SHA1, 30-second steps, 6 digits, the secret shown in base32.
 */
internal object Totp {
    const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private const val SECRET_BYTES = 20
    private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private val random = SecureRandom()

    fun newSecret(): String = base32(ByteArray(SECRET_BYTES).also(random::nextBytes))

    fun step(epochSeconds: Long): Long = epochSeconds / STEP_SECONDS

    fun code(
        secret: String,
        step: Long,
    ): String {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(decodeBase32(secret), "HmacSHA1"))
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary =
            ((hash[offset].toInt() and 0x7f) shl 24) or
                ((hash[offset + 1].toInt() and 0xff) shl 16) or
                ((hash[offset + 2].toInt() and 0xff) shl 8) or
                (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /**
     * The step [code] belongs to within [window] steps either side of [now], or
     * null. The window absorbs a phone clock a little ahead or behind.
     */
    fun matchingStep(
        secret: String,
        code: String,
        now: Long,
        window: Int,
    ): Long? {
        if (code.length != DIGITS || !code.all(Char::isDigit)) return null
        return (-window..window)
            .map { step(now) + it }
            .firstOrNull { java.security.MessageDigest.isEqual(code(secret, it).toByteArray(), code.toByteArray()) }
    }

    fun uri(
        issuer: String,
        account: String,
        secret: String,
    ): String {
        val label = encode("$issuer:$account")
        return "otpauth://totp/$label?secret=$secret&issuer=${encode(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }

    private fun encode(value: String): String =
        java.net.URLEncoder
            .encode(value, Charsets.UTF_8)
            .replace("+", "%20")

    private fun base32(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(BASE32[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(BASE32[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }

    private fun decodeBase32(value: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (char in value.uppercase()) {
            val index = BASE32.indexOf(char)
            require(index >= 0) { "Not a base32 secret" }
            buffer = (buffer shl 5) or index
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }
}
