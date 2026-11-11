package com.jiku.messaging

import com.jiku.messaging.internal.WhatsAppInboundService
import com.jiku.shared.MessageLanguage
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A guest who reads Arabic (JIKU-293) answers the bot in Arabic and reads dates with Western digits. */
class ArabicKeywordsTest {
    @Test
    fun `Arabic keywords match whatever vowel marks and hamza the guest types`() {
        assertTrue(WhatsAppInboundService.normalize("إيقاف") in WhatsAppInboundService.STOP_WORDS)
        assertTrue(WhatsAppInboundService.normalize(" توقّف! ") in WhatsAppInboundService.STOP_WORDS)
        assertTrue(WhatsAppInboundService.normalize("ابدأ") in WhatsAppInboundService.START_WORDS)
        assertTrue(WhatsAppInboundService.normalize("استئناف") in WhatsAppInboundService.START_WORDS)
        assertTrue(WhatsAppInboundService.normalize("تذكرتي") in WhatsAppInboundService.TICKET_WORDS)
        assertEquals("ARRET", WhatsAppInboundService.normalize("  Arrêt ! "))
    }

    @Test
    fun `an event's guest language wins over the organization's, and opens its own pages`() {
        assertEquals(MessageLanguage.ARABIC, MessageLanguage.forGuests("ar", "GN"))
        assertEquals(MessageLanguage.FRENCH, MessageLanguage.forGuests(null, "GN"))
        assertEquals(MessageLanguage.ENGLISH, MessageLanguage.forGuests("xx", "NG"))
        assertEquals("/ar", MessageLanguage.pathPrefix("ar"))
        assertEquals("", MessageLanguage.pathPrefix("fr"))
        assertEquals(MessageLanguage.FRENCH, MessageLanguage.normalize("ar"), "an organizer's own messages stay in French or English")
    }

    @Test
    fun `an Arabic date keeps Western digits, like the phone numbers and codes beside it`() {
        val text = MessageLanguage.formatEventStart(Instant.parse("2026-12-12T18:00:00Z"), "Africa/Conakry", MessageLanguage.ARABIC)

        assertEquals("السبت 12 ديسمبر 2026 الساعة 18:00", text)
    }
}
