package com.jiku.messaging.internal

import com.jiku.shared.MessageLanguage
import com.jiku.shared.WhatsAppReplyOutcome
import com.jiku.shared.WhatsAppRsvpReply
import com.jiku.shared.WhatsAppTicketRequest
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer

/**
 * What a guest does in the WhatsApp chat (JIKU-143). A tapped accept or decline
 * button is matched to the invitation it answers, and only from the number
 * that invitation went to, so a forwarded message answers nothing; the answer
 * goes to the invitation module. Typed keywords: BILLET or TICKET sends the
 * latest invitation's ticket again, STOP silences the number, START lifts it.
 *
 * Answers go back inside the conversation the guest just opened, which Meta
 * does not charge, from the platform number.
 */
@Service
class WhatsAppInboundService(
    private val threads: WhatsAppThreadRepository,
    private val optOuts: WhatsAppOptOutRepository,
    private val providers: MessagingProviderResolver,
    private val catalog: MessageCatalog,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(WhatsAppInboundService::class.java)

    @Transactional
    fun handle(message: InboundWhatsApp) {
        message.buttonId?.let { answer(message.from, it) }
        message.text?.let { keyword(message.from, it) }
    }

    /** Tells the guest how an answer ended when it did not end with a ticket. */
    @EventListener
    fun onOutcome(outcome: WhatsAppReplyOutcome) {
        reply(
            whatsAppDigits(outcome.phone),
            outcome.language,
            "whatsapp.reply.${outcome.kind.name}",
            mapOf("eventName" to outcome.eventName),
        )
    }

    private fun answer(
        from: String,
        buttonId: String,
    ) {
        val answer = WhatsAppReplyPayload.parse(buttonId) ?: return
        val thread = threads.findById(answer.invitationId).orElse(null) ?: return
        if (thread.phone != from) return
        events.publishEvent(WhatsAppRsvpReply(thread.tenantId, thread.invitationId, answer.accepted))
    }

    private fun keyword(
        from: String,
        text: String,
    ) {
        val word = normalize(text)
        val thread = threads.findFirstByPhoneOrderBySentAtDesc(from)
        val language = thread?.language ?: MessageLanguage.FRENCH
        when {
            word in START_WORDS -> {
                optOuts.deleteById(from)
                reply(from, language, "whatsapp.reply.optedIn")
            }

            optOuts.existsById(from) -> Unit

            word in STOP_WORDS -> {
                optOuts.save(WhatsAppOptOut(from))
                reply(from, language, "whatsapp.reply.optedOut")
            }

            word in TICKET_WORDS ->
                if (thread == null) {
                    reply(from, language, "whatsapp.reply.notFound")
                } else {
                    events.publishEvent(WhatsAppTicketRequest(thread.tenantId, thread.invitationId))
                }

            else -> reply(from, language, "whatsapp.reply.help")
        }
    }

    private fun reply(
        phone: String,
        language: String,
        key: String,
        values: Map<String, String> = emptyMap(),
    ) {
        try {
            providers.whatsApp().sender.send(WhatsAppMessage(to = "+$phone", body = catalog.text(language, key, values)))
        } catch (ex: RuntimeException) {
            log.warn("WhatsApp answer {} to {} could not be sent", key, phone, ex)
        }
    }

    private companion object {
        val STOP_WORDS = setOf("STOP", "ARRET", "ARRETER", "DESABONNER", "UNSUBSCRIBE")
        val START_WORDS = setOf("START", "REPRENDRE")
        val TICKET_WORDS = setOf("BILLET", "TICKET", "MON BILLET", "MY TICKET")
        val MARKS = Regex("\\p{M}+")
        val NOT_WORD = Regex("[^A-Z ]+")
        val SPACES = Regex("\\s+")

        /** "  Arrêt ! " and "arret" are the same keyword. */
        fun normalize(text: String): String =
            Normalizer
                .normalize(text.uppercase(), Normalizer.Form.NFD)
                .replace(MARKS, "")
                .replace(NOT_WORD, " ")
                .replace(SPACES, " ")
                .trim()
    }
}
