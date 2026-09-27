package com.jiku.messaging.internal

import com.jiku.shared.MessageLanguage
import com.jiku.shared.OpenCardInbound
import com.jiku.shared.OpenCardReply
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZoneId

/**
 * The messaging side of the Jikū number dedicated to cards (JIKU-185, ADR 106).
 * STOP and START are handled here, as on the platform number; everything else
 * goes to the invitation module as an [OpenCardInbound]. Its answers come back
 * as [OpenCardReply] and are sent from the cards number, inside the
 * conversation the person opened, never to a number that wrote STOP.
 */
@Service
class OpenCardInboundService(
    private val properties: WhatsAppProperties,
    private val cards: CardsWhatsAppSender,
    private val optOuts: WhatsAppOptOutRepository,
    private val catalog: MessageCatalog,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(OpenCardInboundService::class.java)

    fun isCardsNumber(businessNumberId: String?): Boolean =
        businessNumberId != null &&
            properties.meta.cardsPhoneNumberId.isNotBlank() &&
            businessNumberId == properties.meta.cardsPhoneNumberId

    @Transactional
    fun handle(message: InboundWhatsApp) {
        val word = message.text?.let { normalize(it) }
        when {
            word in START_WORDS -> {
                optOuts.deleteById(message.from)
                send(message.from, catalog.text(MessageLanguage.FRENCH, "whatsapp.reply.optedIn"))
            }

            optOuts.existsById(message.from) -> Unit

            word in STOP_WORDS -> {
                optOuts.save(WhatsAppOptOut(message.from))
                send(message.from, catalog.text(MessageLanguage.FRENCH, "whatsapp.reply.optedOut"))
            }

            else -> events.publishEvent(OpenCardInbound(message.from, message.profileName, message.text, message.buttonId))
        }
    }

    @EventListener
    fun onReply(reply: OpenCardReply) {
        val phone = whatsAppDigits(reply.phone)
        if (optOuts.existsById(phone)) return
        val values =
            reply.values +
                listOfNotNull(
                    reply.eventStart?.let { start ->
                        "when" to "\n" + catalog.formatDate(reply.language, "date.reminder", start, ZoneId.of(reply.eventTimezone ?: "UTC"))
                    },
                )
        val buttons = reply.buttons.map { WhatsAppButton(it.id, catalog.text(reply.language, it.titleKey)) }
        send(phone, catalog.text(reply.language, reply.key, values), buttons, reply.imageUrl)
    }

    private fun send(
        phone: String,
        body: String,
        buttons: List<WhatsAppButton> = emptyList(),
        imageUrl: String? = null,
    ) {
        try {
            cards.sender.send(WhatsAppMessage(to = "+$phone", body = body, buttons = buttons, imageUrl = imageUrl))
        } catch (ex: RuntimeException) {
            log.warn("WhatsApp card answer to {} could not be sent", phone, ex)
        }
    }

    private companion object {
        val STOP_WORDS = setOf("STOP", "ARRET", "ARRETER", "DESABONNER", "UNSUBSCRIBE")
        val START_WORDS = setOf("START", "REPRENDRE")
        val MARKS = Regex("\\p{M}+")
        val NOT_WORD = Regex("[^A-Z ]+")
        val SPACES = Regex("\\s+")

        fun normalize(text: String): String =
            java.text.Normalizer
                .normalize(text.uppercase(), java.text.Normalizer.Form.NFD)
                .replace(MARKS, "")
                .replace(NOT_WORD, " ")
                .replace(SPACES, " ")
                .trim()
    }
}
