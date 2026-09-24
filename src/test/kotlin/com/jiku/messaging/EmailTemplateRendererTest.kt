package com.jiku.messaging

import com.jiku.messaging.internal.CancellationEmail
import com.jiku.messaging.internal.EmailTemplateRenderer
import com.jiku.messaging.internal.InvitationEmail
import com.jiku.messaging.internal.MessageCatalog
import com.jiku.messaging.internal.TenantTemplateRepository
import com.jiku.messaging.internal.TenantTemplateResolver
import com.jiku.messaging.internal.WhatsAppInvitation
import com.jiku.messaging.internal.WhatsAppTemplateRenderer
import com.jiku.shared.ManualPaymentNotice
import com.jiku.shared.MemberInvitationNotice
import com.jiku.shared.MessageLanguage
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmailTemplateRendererTest {
    private val catalog = MessageCatalog { MessageLanguage.FRENCH }
    private val resolver = TenantTemplateResolver(unused<TenantTemplateRepository>(), unused<ApplicationEventPublisher>())
    private val renderer = EmailTemplateRenderer(resolver, catalog)
    private val whatsApp = WhatsAppTemplateRenderer(resolver, catalog)

    @Test
    fun `an invitation is written in the organization's language with its brand`() {
        val french = renderer.renderInvitation(invitation(), MessageLanguage.FRENCH)
        val english = renderer.renderInvitation(invitation(), MessageLanguage.ENGLISH)

        assertEquals("Invitation : Gala <25 ans>", french.subject)
        assertEquals("You're invited: Gala <25 ans>", english.subject)
        assertTrue(french.html.contains("""<html lang="fr">"""))
        assertTrue(english.html.contains("""<html lang="en">"""))
        assertTrue(french.html.contains("Quand"))
        assertTrue(english.html.contains("Where"))
        assertTrue(french.html.contains("#7C3AED"), "the organizer's colour carries the accent")
        assertTrue(french.html.contains("Maison Aminata"))
    }

    @Test
    fun `user values are escaped in the html and nothing is left unsubstituted`() {
        val email = renderer.renderInvitation(invitation(), MessageLanguage.FRENCH)

        assertTrue(email.html.contains("Gala &lt;25 ans&gt;"))
        assertFalse(email.html.contains("<25 ans>"))
        assertFalse(email.html.contains("{{"), "every variable is substituted")
    }

    @Test
    fun `a cancellation without date or place keeps no empty details`() {
        val email =
            renderer.renderCancellation(
                CancellationEmail(
                    recipientEmail = "awa@example.com",
                    recipientName = "Awa",
                    eventName = "Gala",
                    eventWhen = null,
                    eventLocation = null,
                    organizerName = "Maison Aminata",
                    primaryColor = "#7C3AED",
                    logoUrl = null,
                ),
                MessageLanguage.ENGLISH,
            )

        assertEquals("Gala has been cancelled", email.subject)
        assertFalse(email.html.contains("When"))
        assertFalse(email.html.contains("{{"))
    }

    @Test
    fun `platform emails wear the platform brand, not the organizer's`() {
        val notice =
            ManualPaymentNotice(
                kind = ManualPaymentNotice.KIND_REJECTED,
                paymentId = UUID.randomUUID(),
                tenantId = UUID.randomUUID().toString(),
                eventId = UUID.randomUUID(),
                tier = "PRO",
                amountMinor = 150000,
                currency = "GNF",
                reference = "JK-42",
                organizerName = "Maison Aminata",
                organizerEmail = "owner@example.com",
                note = "Montant incomplet",
            )

        val email = renderer.renderManualPaymentRejected(notice, MessageLanguage.FRENCH)

        assertTrue(email.html.contains("<title>Jikū</title>"))
        assertTrue(email.html.contains("150 000 GNF"))
        assertTrue(email.html.contains("Montant incomplet"))
        assertFalse(email.html.contains("{{"))
    }

    @Test
    fun `a member invitation names the role in the organization's language`() {
        val email =
            renderer.renderMemberInvitation(
                MemberInvitationNotice(
                    email = "new@example.com",
                    organizationName = "Maison Aminata",
                    inviterEmail = "owner@example.com",
                    role = "ADMIN",
                    actionUrl = "https://jiku.app/join?token=abc",
                    language = MessageLanguage.FRENCH,
                ),
            )

        assertTrue(email.html.contains("administrateur"))
        assertEquals("Maison Aminata vous invite sur Jikū", email.subject)
    }

    @Test
    fun `operational dates are written in the recipient's language`() {
        val instant = Instant.parse("2026-11-03T15:00:00Z")

        assertEquals("3 novembre 2026 à 15:00 (UTC)", catalog.formatDate("fr", "date.operational", instant, ZoneOffset.UTC))
        assertEquals("3 November 2026 at 15:00 (UTC)", catalog.formatDate("en", "date.operational", instant, ZoneOffset.UTC))
    }

    @Test
    fun `a french whatsapp invitation keeps the weekday in lower case mid-sentence`() {
        val text =
            whatsApp.renderInvitation(
                WhatsAppInvitation(
                    recipientPhone = "+224600000000",
                    recipientName = "Awa",
                    eventName = "Gala",
                    eventWhen = "Mardi 3 novembre 2026 à 15:00",
                    organizerName = "Maison Aminata",
                    invitationUrl = "https://jiku.app/invitation/abc",
                ),
                MessageLanguage.FRENCH,
            )

        assertTrue(text.startsWith("Bonjour Awa, Maison Aminata vous invite à Gala le mardi 3 novembre 2026 à 15:00."))
    }

    private fun invitation() =
        InvitationEmail(
            recipientEmail = "awa@example.com",
            recipientName = "Awa Diallo",
            eventName = "Gala <25 ans>",
            eventWhen = "Mardi 3 novembre 2026 à 15:00",
            eventLocation = "Avenue de la République",
            organizerName = "Maison Aminata",
            primaryColor = "#7C3AED",
            logoUrl = null,
            invitationUrl = "https://jiku.app/invitation/abc",
        )

    /** Without a tenant in context the resolver never reaches its collaborators. */
    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Unexpected call to ${method.name}")
        } as T
}
