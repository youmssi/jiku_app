package com.jiku.messaging

import com.jiku.messaging.internal.BrevoFeedbackMapper
import com.jiku.messaging.internal.BrevoWebhookAuth
import com.jiku.messaging.internal.EmailFeedback
import com.jiku.messaging.internal.FeedbackEvent
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrevoFeedbackTest {
    private val objectMapper = ObjectMapper()

    private fun map(json: String) = BrevoFeedbackMapper.map(objectMapper.readTree(json))

    @Test
    fun `accepts only the configured bearer token`() {
        assertTrue(BrevoWebhookAuth.matches("brevo-token", "Bearer brevo-token"))
        assertFalse(BrevoWebhookAuth.matches("brevo-token", "Bearer other-token"))
        assertFalse(BrevoWebhookAuth.matches("brevo-token", "brevo-token"))
        assertFalse(BrevoWebhookAuth.matches("brevo-token", null))
    }

    @Test
    fun `maps bounces, invalid addresses and spam reports`() {
        assertEquals(
            listOf(FeedbackEvent("a@example.com", EmailFeedback.TYPE_HARD_BOUNCE)),
            map("""{"event":"hard_bounce","email":"a@example.com"}"""),
        )
        assertEquals(
            listOf(FeedbackEvent("b@example.com", EmailFeedback.TYPE_HARD_BOUNCE)),
            map("""{"event":"invalid_email","email":"b@example.com"}"""),
        )
        assertEquals(
            listOf(FeedbackEvent("c@example.com", EmailFeedback.TYPE_SOFT_BOUNCE)),
            map("""{"event":"soft_bounce","email":"c@example.com"}"""),
        )
        assertEquals(
            listOf(FeedbackEvent("d@example.com", EmailFeedback.TYPE_COMPLAINT)),
            map("""{"event":"spam","email":"d@example.com"}"""),
        )
    }

    @Test
    fun `reads a batch and ignores deliveries, opens and blocked sends`() {
        val events =
            map(
                """[{"event":"delivered","email":"x@example.com"},{"event":"blocked","email":"y@example.com"},
                   {"event":"hard_bounce","email":"z@example.com"},{"event":"hard_bounce"}]""",
            )
        assertEquals(listOf(FeedbackEvent("z@example.com", EmailFeedback.TYPE_HARD_BOUNCE)), events)
    }
}
