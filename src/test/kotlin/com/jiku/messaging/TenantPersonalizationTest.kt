package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.TenantPersonalizationService
import com.jiku.messaging.internal.TenantTemplate
import com.jiku.messaging.internal.TenantTemplateRepository
import com.jiku.messaging.internal.VocabularyUpdate
import com.jiku.messaging.internal.WhatsAppInvitation
import com.jiku.messaging.internal.WhatsAppTemplateRenderer
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Vocabulaire et gabarits par tenant (JIKU-91) — DoD : un gabarit invalide ne
 * fait jamais échouer un envoi (repli sur le défaut) ; le gabarit d'un tenant
 * n'atteint jamais un autre tenant. L'infrastructure d'envoi est le transport log.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TenantPersonalizationTest {
    @Autowired
    lateinit var templates: TenantTemplateRepository

    @Autowired
    lateinit var personalization: TenantPersonalizationService

    @Autowired
    lateinit var whatsappRenderer: WhatsAppTemplateRenderer

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `a tenant override is used at send time and another tenant keeps the default`() {
        val tenantA = "perso-a"
        val tenantB = "perso-b"
        TenantContext.set(tenantA)
        templates.save(
            TenantTemplate(
                name = "invitation",
                channel = "WHATSAPP",
                body = "Bienvenue chez Aminata, {{guestName}} — confirmez : {{invitationUrl}}",
            ),
        )
        TenantContext.clear()

        val invite = invitation()
        TenantContext.set(tenantA)
        val fromA = whatsappRenderer.renderInvitation(invite)
        assertTrue(fromA.contains("Bienvenue chez Aminata"), "tenant A must use its override")
        assertFalse(fromA.contains("{{guestName}}"))
        TenantContext.clear()

        TenantContext.set(tenantB)
        val fromB = whatsappRenderer.renderInvitation(invite)
        assertFalse(fromB.contains("Bienvenue chez Aminata"), "tenant B must never see A's override")
        assertTrue(fromB.contains("has invited you"), "tenant B keeps the default wording")
        TenantContext.clear()
    }

    @Test
    fun `an invalid override falls back to the default and never fails the send`() {
        val tenant = "perso-invalid"
        TenantContext.set(tenant)
        templates.save(
            TenantTemplate(
                name = "invitation",
                channel = "WHATSAPP",
                body = "Invitation {{guestName}} with an unknown {{nopeToken}} here",
            ),
        )
        TenantContext.clear()

        val invite = invitation()
        TenantContext.set(tenant)
        val rendered = whatsappRenderer.renderInvitation(invite)
        TenantContext.clear()

        assertFalse(rendered.contains("{{nopeToken}}"), "invalid override must not leak its unknown variable")
        assertTrue(rendered.contains("has invited you"), "send falls back to the default body")
        assertTrue(rendered.contains(invite.recipientName))
    }

    @Test
    fun `vocabulary overrides resolve to the tenant value and reset to default`() {
        val tenant = "perso-vocab"
        TenantContext.set(tenant)

        val before = personalization.vocabulary()
        assertEquals("rendez-vous", before.first { it.key == "appointment" }.value)
        assertFalse(before.first { it.key == "appointment" }.overridden)

        val updated = personalization.saveVocabulary(listOf(VocabularyUpdate(key = "appointment", value = "consultation")))
        val appointment = updated.first { it.key == "appointment" }
        assertEquals("consultation", appointment.value)
        assertTrue(appointment.overridden)
        assertNotEquals("consultation", personalization.vocabulary().first { it.key == "professional" }.value)

        val reset = personalization.saveVocabulary(listOf(VocabularyUpdate(key = "appointment", value = null)))
        assertEquals("rendez-vous", reset.first { it.key == "appointment" }.value)
        assertFalse(reset.first { it.key == "appointment" }.overridden)
        TenantContext.clear()
    }

    private fun invitation(): WhatsAppInvitation =
        WhatsAppInvitation(
            recipientPhone = "+224600000070",
            recipientName = "Awa Diallo",
            eventName = "Coupe + soin",
            eventWhen = "Tuesday 3 Nov at 15:00",
            organizerName = "Salon Aminata",
            invitationUrl = "https://jiku.app/r/abc123",
        )
}
