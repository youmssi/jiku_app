package com.jiku.messaging

import com.jiku.messaging.internal.InboundWhatsApp
import com.jiku.messaging.internal.MetaWebhookSignature
import com.jiku.messaging.internal.WhatsAppReplyPayload
import com.jiku.messaging.internal.WhatsAppWebhookParser
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WhatsAppWebhookParsingTest {
    private val mapper = JsonMapper.builder().build()

    private fun call(vararg messages: String) =
        """{"object":"whatsapp_business_account","entry":[{"changes":[{"field":"messages","value":{"messages":[${messages.joinToString(
            ",",
        )}]}}]}]}"""

    @Test
    fun `reads template buttons, session buttons and text, and skips the rest`() {
        val body =
            call(
                """{"from":"224620000001","type":"button","button":{"payload":"RSVP_YES:abc","text":"Je serai présent"}}""",
                """{"from":"224620000002","type":"interactive","interactive":{"type":"button_reply","button_reply":{"id":"RSVP_NO:abc","title":"Non"}}}""",
                """{"from":"224620000003","type":"text","text":{"body":"Billet"}}""",
                """{"from":"224620000004","type":"image","image":{"id":"m1"}}""",
            )

        assertEquals(
            listOf(
                InboundWhatsApp("224620000001", buttonId = "RSVP_YES:abc"),
                InboundWhatsApp("224620000002", buttonId = "RSVP_NO:abc"),
                InboundWhatsApp("224620000003", text = "Billet"),
            ),
            WhatsAppWebhookParser.messages(mapper.readTree(body)),
        )
    }

    @Test
    fun `a status update carries no reply`() {
        val body = """{"entry":[{"changes":[{"value":{"statuses":[{"id":"wamid.1","status":"read"}]}}]}]}"""

        assertEquals(emptyList(), WhatsAppWebhookParser.messages(mapper.readTree(body)))
    }

    @Test
    fun `accepts only Meta's signature made with the app secret`() {
        val body = call()
        val signature = "sha256=" + MetaWebhookSignature.sign("app-secret", body)

        assertTrue(MetaWebhookSignature.verify("app-secret", body, signature))
        assertFalse(MetaWebhookSignature.verify("other-secret", body, signature))
        assertFalse(MetaWebhookSignature.verify("app-secret", "$body ", signature))
        assertFalse(MetaWebhookSignature.verify("app-secret", body, null))
        assertFalse(MetaWebhookSignature.verify("", body, signature))
    }

    @Test
    fun `button ids carry the answer and the invitation`() {
        val invitationId = UUID.randomUUID()

        assertEquals(WhatsAppReplyPayload.Answer(invitationId, true), WhatsAppReplyPayload.parse(WhatsAppReplyPayload.accept(invitationId)))
        assertEquals(
            WhatsAppReplyPayload.Answer(invitationId, false),
            WhatsAppReplyPayload.parse(WhatsAppReplyPayload.decline(invitationId)),
        )
        assertNull(WhatsAppReplyPayload.parse("RSVP_YES:not-a-uuid"))
        assertNull(WhatsAppReplyPayload.parse("SOMETHING:$invitationId"))
    }
}
