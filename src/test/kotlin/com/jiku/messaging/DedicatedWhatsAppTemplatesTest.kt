package com.jiku.messaging

import com.jiku.messaging.internal.MessageCatalog
import com.jiku.messaging.internal.WhatsAppCancellation
import com.jiku.messaging.internal.WhatsAppInvitation
import com.jiku.messaging.internal.WhatsAppTemplateCalls
import com.jiku.messaging.internal.WhatsAppTemplateKind
import com.jiku.shared.TenantLanguage
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dedicated templates (JIKU-210) as Meta will review them: every kind in
 * French and English, numbered parameters used once each, no parameter at the
 * start or end of the text, an example per parameter, a short footer.
 */
class DedicatedWhatsAppTemplatesTest {
    private val definitions: JsonNode =
        ClassPathResource("whatsapp-templates/meta/dedicated.json").inputStream.use { JsonMapper.builder().build().readTree(it) }
    private val calls = WhatsAppTemplateCalls(MessageCatalog(TenantLanguage { "fr" }))
    private val variable = Regex("\\{\\{(\\d+)}}")

    @Test
    fun `every template is defined in both languages the way Meta accepts it`() {
        for (kind in WhatsAppTemplateKind.entries) {
            val definition = definitions.path(kind.key)
            val parameters = definition.path("parameters").size()
            for (language in listOf("fr", "en")) {
                val components = definition.path(language).toList()
                val body = components.single { it.path("type").asString("") == "BODY" }
                val text = body.path("text").asString("")
                val numbers = variable.findAll(text).map { it.groupValues[1].toInt() }.toList()
                assertEquals((1..parameters).toList(), numbers, "${kind.key} $language")
                assertFalse(text.trim().startsWith("{{") || text.trim().endsWith("}}"), "${kind.key} $language")
                assertEquals(
                    parameters,
                    body
                        .path("example")
                        .path("body_text")
                        .path(0)
                        .size(),
                    "${kind.key} $language",
                )
                val footer = components.single { it.path("type").asString("") == "FOOTER" }.path("text").asString("")
                assertTrue(footer.length <= 60 && "STOP" in footer, "${kind.key} $language")
                val buttons = components.filter { it.path("type").asString("") == "BUTTONS" }.flatMap { it.path("buttons").toList() }
                assertEquals(if (kind == WhatsAppTemplateKind.INVITATION_RSVP) 2 else 0, buttons.size, "${kind.key} $language")
                assertEquals(kind == WhatsAppTemplateKind.TICKET, components.any { it.path("format").asString("") == "IMAGE" })
            }
        }
    }

    @Test
    fun `parameters follow the template's order, on one line, with a wording for what is missing`() {
        val invitation =
            WhatsAppInvitation(
                recipientPhone = "+224620000001",
                recipientName = "  ",
                eventName = "Mariage\nd'Awa",
                eventWhen = "Samedi 12 octobre à 18 h",
                organizerName = "Famille Diallo",
                invitationUrl = "https://jiku.app/r/abc",
            )

        val withLink = calls.invitation(invitation, "fr-FR", withButtons = false)
        assertEquals(WhatsAppTemplateKind.INVITATION, withLink.kind)
        assertEquals("fr", withLink.language)
        assertEquals(
            listOf("à vous", "Famille Diallo", "Mariage d'Awa", "samedi 12 octobre à 18 h", "https://jiku.app/r/abc"),
            withLink.parameters,
        )

        val withButtons = calls.invitation(invitation.copy(eventWhen = null), "en", withButtons = true)
        assertEquals(WhatsAppTemplateKind.INVITATION_RSVP, withButtons.kind)
        assertEquals(listOf("there", "Famille Diallo", "Mariage d'Awa", "date to be confirmed"), withButtons.parameters)

        val cancelled =
            calls.cancellation(WhatsAppCancellation("+224620000001", "Awa", "Gala", "Saturday 4 May", "Club"), "en")
        assertEquals(listOf("Awa", "Club", "Gala", "Saturday 4 May"), cancelled.parameters)

        assertEquals(listOf("Awa", "l'accueil"), calls.clientCalled("Awa", null, "fr").parameters)
    }

    @Test
    fun `the organization's own words never carry a link`() {
        val invitation =
            WhatsAppInvitation(
                recipientPhone = "+224620000001",
                recipientName = "Awa",
                eventName = "Gala gratuit sur https://bit.ly/x1 et www.promo.xyz/win",
                eventWhen = null,
                organizerName = "Lots.top Events",
                invitationUrl = "https://jiku.app/r/abc",
            )

        val call = calls.invitation(invitation, "fr", withButtons = false)

        assertEquals("Gala gratuit sur et", call.parameters[2])
        assertEquals("Events", call.parameters[1])
        assertEquals("https://jiku.app/r/abc", call.parameters[4])
    }
}
