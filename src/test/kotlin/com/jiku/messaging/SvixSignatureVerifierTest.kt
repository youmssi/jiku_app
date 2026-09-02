package com.jiku.messaging

import com.jiku.messaging.internal.SvixSignatureVerifier
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvixSignatureVerifierTest {
    private val secretBytes = "0123456789abcdef0123456789abcdef".toByteArray()
    private val secret = "whsec_" + Base64.getEncoder().encodeToString(secretBytes)
    private val now = Instant.parse("2026-07-09T12:00:00Z")
    private val payload = """{"type":"email.bounced","data":{"to":["guest@example.com"]}}"""

    private fun sign(
        id: String,
        timestamp: String,
        body: String,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal("$id.$timestamp.$body".toByteArray()))
    }

    @Test
    fun `accepts a correctly signed payload within the tolerance window`() {
        val timestamp = now.epochSecond.toString()
        val signature = "v1," + sign("msg_1", timestamp, payload)
        assertTrue(SvixSignatureVerifier.verify(secret, "msg_1", timestamp, signature, payload, now))
    }

    @Test
    fun `accepts when one of several signature candidates matches`() {
        val timestamp = now.epochSecond.toString()
        val signature = "v1,notthisone= v1," + sign("msg_1", timestamp, payload)
        assertTrue(SvixSignatureVerifier.verify(secret, "msg_1", timestamp, signature, payload, now))
    }

    @Test
    fun `rejects a tampered payload`() {
        val timestamp = now.epochSecond.toString()
        val signature = "v1," + sign("msg_1", timestamp, payload)
        val tampered = payload.replace("guest@example.com", "other@example.com")
        assertFalse(SvixSignatureVerifier.verify(secret, "msg_1", timestamp, signature, tampered, now))
    }

    @Test
    fun `rejects a timestamp outside the tolerance window`() {
        val stale = now.minusSeconds(600).epochSecond.toString()
        val signature = "v1," + sign("msg_1", stale, payload)
        assertFalse(SvixSignatureVerifier.verify(secret, "msg_1", stale, signature, payload, now))
    }

    @Test
    fun `rejects missing headers`() {
        val timestamp = now.epochSecond.toString()
        val signature = "v1," + sign("msg_1", timestamp, payload)
        assertFalse(SvixSignatureVerifier.verify(secret, null, timestamp, signature, payload, now))
        assertFalse(SvixSignatureVerifier.verify(secret, "msg_1", null, signature, payload, now))
        assertFalse(SvixSignatureVerifier.verify(secret, "msg_1", timestamp, null, payload, now))
    }
}
