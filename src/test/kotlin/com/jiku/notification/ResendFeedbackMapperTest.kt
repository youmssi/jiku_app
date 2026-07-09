package com.jiku.notification

import com.jiku.notification.internal.EmailFeedback
import com.jiku.notification.internal.ResendFeedbackMapper
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResendFeedbackMapperTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `maps a permanent bounce to a hard bounce for every recipient`() {
        val events =
            ResendFeedbackMapper.map(
                mapper.readTree(
                    """
                    {"type":"email.bounced","data":{"to":["a@example.com","b@example.com"],
                     "bounce":{"type":"Permanent","subType":"General"}}}
                    """.trimIndent(),
                ),
            )
        assertEquals(2, events.size)
        assertEquals(EmailFeedback.TYPE_HARD_BOUNCE, events[0].type)
        assertEquals("a@example.com", events[0].recipient)
        assertEquals("b@example.com", events[1].recipient)
    }

    @Test
    fun `maps a transient bounce to a soft bounce`() {
        val events =
            ResendFeedbackMapper.map(
                mapper.readTree(
                    """{"type":"email.bounced","data":{"to":["a@example.com"],"bounce":{"type":"Transient"}}}""",
                ),
            )
        assertEquals(listOf(EmailFeedback.TYPE_SOFT_BOUNCE), events.map { it.type })
    }

    @Test
    fun `defaults an unclassified bounce to hard so reputation is never under-counted`() {
        val events =
            ResendFeedbackMapper.map(
                mapper.readTree("""{"type":"email.bounced","data":{"to":["a@example.com"]}}"""),
            )
        assertEquals(listOf(EmailFeedback.TYPE_HARD_BOUNCE), events.map { it.type })
    }

    @Test
    fun `maps a complaint`() {
        val events =
            ResendFeedbackMapper.map(
                mapper.readTree("""{"type":"email.complained","data":{"to":["a@example.com"]}}"""),
            )
        assertEquals(listOf(EmailFeedback.TYPE_COMPLAINT), events.map { it.type })
    }

    @Test
    fun `ignores event types that do not affect reputation`() {
        val events =
            ResendFeedbackMapper.map(
                mapper.readTree("""{"type":"email.delivered","data":{"to":["a@example.com"]}}"""),
            )
        assertTrue(events.isEmpty())
    }
}
