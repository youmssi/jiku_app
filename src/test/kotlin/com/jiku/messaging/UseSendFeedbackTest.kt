package com.jiku.messaging

import com.jiku.messaging.internal.EmailFeedback
import com.jiku.messaging.internal.FeedbackEvent
import com.jiku.messaging.internal.UseSendFeedbackMapper
import com.jiku.messaging.internal.UseSendSignatureVerifier
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UseSendFeedbackTest {
    private val secret = "whsec_usesend_test_secret"
    private val now = Instant.parse("2026-10-02T12:00:00Z")
    private val payload = """{"type":"email.bounced","data":{"to":["guest@example.com"]}}"""
    private val objectMapper = ObjectMapper()

    private fun sign(
        timestamp: String,
        body: String,
        key: String = secret,
    ): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return "v1=" + HexFormat.of().formatHex(mac.doFinal("$timestamp.$body".toByteArray()))
    }

    @Test
    fun `accepts a correctly signed payload within the tolerance window`() {
        val timestamp = now.toEpochMilli().toString()
        assertTrue(UseSendSignatureVerifier.verify(secret, timestamp, sign(timestamp, payload), payload, now))
    }

    @Test
    fun `rejects a payload changed after signing`() {
        val timestamp = now.toEpochMilli().toString()
        val signature = sign(timestamp, payload)
        assertFalse(UseSendSignatureVerifier.verify(secret, timestamp, signature, payload.replace("guest", "other"), now))
    }

    @Test
    fun `rejects a signature made with another secret`() {
        val timestamp = now.toEpochMilli().toString()
        val signature = sign(timestamp, payload, key = "another-secret")
        assertFalse(UseSendSignatureVerifier.verify(secret, timestamp, signature, payload, now))
    }

    @Test
    fun `rejects a delivery older than five minutes`() {
        val timestamp = now.minusSeconds(301).toEpochMilli().toString()
        assertFalse(UseSendSignatureVerifier.verify(secret, timestamp, sign(timestamp, payload), payload, now))
    }

    @Test
    fun `rejects missing or malformed headers`() {
        val timestamp = now.toEpochMilli().toString()
        assertFalse(UseSendSignatureVerifier.verify(secret, null, sign(timestamp, payload), payload, now))
        assertFalse(UseSendSignatureVerifier.verify(secret, timestamp, null, payload, now))
        assertFalse(UseSendSignatureVerifier.verify(secret, "not-a-number", sign(timestamp, payload), payload, now))
    }

    @Test
    fun `maps a permanent bounce to a hard bounce for every recipient`() {
        val root =
            objectMapper.readTree(
                """{"type":"email.bounced","data":{"to":["a@example.com","b@example.com"],"bounce":{"type":"Permanent","subType":"NoEmail"}}}""",
            )
        assertEquals(
            listOf(
                FeedbackEvent("a@example.com", EmailFeedback.TYPE_HARD_BOUNCE),
                FeedbackEvent("b@example.com", EmailFeedback.TYPE_HARD_BOUNCE),
            ),
            UseSendFeedbackMapper.map(root),
        )
    }

    @Test
    fun `maps a transient bounce to a soft bounce`() {
        val root =
            objectMapper.readTree(
                """{"type":"email.bounced","data":{"to":["a@example.com"],"bounce":{"type":"Transient","subType":"MailboxFull"}}}""",
            )
        assertEquals(listOf(FeedbackEvent("a@example.com", EmailFeedback.TYPE_SOFT_BOUNCE)), UseSendFeedbackMapper.map(root))
    }

    @Test
    fun `counts an undetermined bounce as hard so it never under-counts`() {
        val root =
            objectMapper.readTree(
                """{"type":"email.bounced","data":{"to":["a@example.com"],"bounce":{"type":"Undetermined"}}}""",
            )
        assertEquals(listOf(FeedbackEvent("a@example.com", EmailFeedback.TYPE_HARD_BOUNCE)), UseSendFeedbackMapper.map(root))
    }

    @Test
    fun `maps a complaint`() {
        val root = objectMapper.readTree("""{"type":"email.complained","data":{"to":["a@example.com"]}}""")
        assertEquals(listOf(FeedbackEvent("a@example.com", EmailFeedback.TYPE_COMPLAINT)), UseSendFeedbackMapper.map(root))
    }

    @Test
    fun `ignores deliveries, opens and the dashboard test event`() {
        assertTrue(
            UseSendFeedbackMapper.map(objectMapper.readTree("""{"type":"email.delivered","data":{"to":["a@example.com"]}}""")).isEmpty(),
        )
        assertTrue(
            UseSendFeedbackMapper.map(objectMapper.readTree("""{"type":"email.opened","data":{"to":["a@example.com"]}}""")).isEmpty(),
        )
        assertTrue(UseSendFeedbackMapper.map(objectMapper.readTree("""{"test":true,"webhookId":"wh_1"}""")).isEmpty())
    }
}
