package com.jiku.messaging

import com.jiku.messaging.internal.EmailTemplateRenderer
import com.jiku.messaging.internal.InvitationEmail
import com.jiku.messaging.internal.MessageCatalog
import com.jiku.messaging.internal.NotificationEmailProperties
import com.jiku.messaging.internal.PlainText
import com.jiku.messaging.internal.TenantTemplateRepository
import com.jiku.messaging.internal.TenantTemplateResolver
import com.jiku.shared.MessageLanguage
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlainTextTest {
    @Test
    fun `keeps the reading order, link addresses and paragraph breaks`() {
        val html =
            """
            <html><head><title>Jikū</title><style>.x { color: red; }</style></head>
            <body><div style="display:none;max-height:0;">Preheader&#8202;&#847;</div>
            <h1>Bonjour&nbsp;Awa</h1><p>Gala &lt;25 ans&gt; &amp; dîner.</p>
            <table><tr><td>Invités</td><td><b>300</b></td></tr></table>
            <img src="logo.png" alt="Jikū" />
            <a href="https://hellojiku.com/v/AB12">Répondre</a><br />
            <a href="https://hellojiku.com">https://hellojiku.com</a></body></html>
            """.trimIndent()

        assertEquals(
            "Bonjour Awa\n\nGala <25 ans> & dîner.\n\nInvités 300\n\n" +
                "Répondre (https://hellojiku.com/v/AB12)\nhttps://hellojiku.com",
            PlainText.fromHtml(html),
        )
    }

    @Test
    fun `a label sits above its value while body paragraphs keep their blank line`() {
        val html =
            """
            <table><tr><td class="px ink"><p>Premier paragraphe.</p><p>Second paragraphe.</p></td></tr>
            <tr><td><p>Invité</p><p>Mariam Diallo</p></td><td><p>Catégorie</p><p>VIP</p></td></tr></table>
            """.trimIndent()

        assertEquals(
            "Premier paragraphe.\n\nSecond paragraphe.\n\nInvité\nMariam Diallo\nCatégorie\nVIP",
            PlainText.fromHtml(html),
        )
    }

    @Test
    fun `a rendered invitation reads cleanly as text`() {
        val renderer =
            EmailTemplateRenderer(
                TenantTemplateResolver(unused<TenantTemplateRepository>(), unused<ApplicationEventPublisher>()),
                MessageCatalog { MessageLanguage.FRENCH },
                NotificationEmailProperties(siteUrl = "https://hellojiku.com", assetBaseUrl = "https://hellojiku.com/email"),
            )
        val email =
            InvitationEmail(
                recipientEmail = "awa@example.com",
                recipientName = "Awa",
                eventName = "Gala <25 ans>",
                eventWhen = "samedi 14 décembre à 16:00",
                eventLocation = "Salle Kaloum",
                organizerName = "Maison Aminata",
                primaryColor = "#7C2D12",
                logoUrl = null,
                invitationUrl = "https://hellojiku.com/v/KE3E7D5A",
            )

        val text = PlainText.fromHtml(renderer.renderInvitation(email, MessageLanguage.FRENCH).html)

        assertTrue(text.contains("Gala <25 ans>"))
        assertTrue(text.contains("Bonjour Awa"))
        assertTrue(text.contains("(https://hellojiku.com/v/KE3E7D5A)"), "the call to action keeps its address")
        assertTrue(text.contains("Salle Kaloum"))
        assertFalse(text.contains("<td"), "no markup is left")
        assertFalse(text.contains("color:"), "no style is left")
        assertFalse(text.contains("\n\n\n"), "no run of blank lines")
    }

    private inline fun <reified T : Any> unused(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Unexpected call to ${method.name}")
        } as T
}
