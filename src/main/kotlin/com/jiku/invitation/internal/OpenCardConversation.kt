package com.jiku.invitation.internal

import com.jiku.shared.MessageLanguage
import com.jiku.shared.OpenCardButton
import com.jiku.shared.OpenCardInbound
import com.jiku.shared.OpenCardReply
import com.jiku.shared.TenantContext
import com.jiku.shared.async.Executors
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Answering an open invitation without leaving WhatsApp (JIKU-185, ADR 106).
 * The card's link opens a chat with the Jikū number dedicated to cards, with a
 * message holding the invitation's code:
 *
 * 1. The code is answered with the event and three buttons: coming, maybe,
 *    not coming.
 * 2. "Coming" records a yes at once (the place is held), then asks how many
 *    people come along, with buttons up to two and a typed number beyond.
 * 3. The last answer comes back with the ticket and its QR code.
 *
 * The number is the one WhatsApp reports, so it needs no check; the name is the
 * one the person shows in WhatsApp. Every answer goes through
 * [OpenInvitationService], with the same rules as the web page. Runs off the
 * webhook's thread, so Meta is acknowledged at once.
 */
@Component
class OpenCardConversation(
    private val service: OpenInvitationService,
    private val openInvitations: OpenInvitationRepository,
    private val threads: OpenCardThreadRepository,
    private val tenants: TenantModuleApi,
    private val ticketNotices: TicketNotices,
    private val publisher: ApplicationEventPublisher,
    private val properties: OpenInvitationProperties,
) {
    @Async(Executors.URGENT)
    @EventListener
    fun onInbound(message: OpenCardInbound) {
        val button = message.buttonId?.let { CardButton.parse(it) }
        if (button != null) {
            onButton(message, button)
            return
        }
        val text = message.text?.trim().orEmpty()
        val code = CODE.findAll(text.uppercase()).map { it.value }.firstOrNull { openInvitations.route(it) != null }
        val number = text.toIntOrNull()
        val thread = threads.findById(message.from).orElse(null)
        when {
            code != null -> greet(message.from, code)
            number != null && thread != null -> answerYes(message, thread.code, number)
            CODE.containsMatchIn(text.uppercase()) -> reply(message.from, MessageLanguage.FRENCH, "whatsapp.card.unknown")
            else -> reply(message.from, MessageLanguage.FRENCH, "whatsapp.card.help")
        }
    }

    private fun onButton(
        message: OpenCardInbound,
        button: CardButton,
    ) {
        when (button.action) {
            ACTION_YES -> holdYes(message, button.code)
            ACTION_MAYBE -> answerOther(message, button.code, OpenAnswer.MAYBE)
            ACTION_NO -> answerOther(message, button.code, OpenAnswer.NO)
            else ->
                button.action
                    .removePrefix(ACTION_COMPANIONS)
                    .toIntOrNull()
                    ?.let { answerYes(message, button.code, it) }
        }
    }

    /** The event and the three answers, with the privacy notice. */
    private fun greet(
        phone: String,
        code: String,
    ) = inInvitation(phone, code) { eventId, language ->
        val view = service.publicView(eventId)
        if (!view.accepting) return@inInvitation closed(phone, language, view)
        threads.save(
            threads.findById(phone).orElse(OpenCardThread(phone, code)).apply {
                this.code = code
                updatedAt = Instant.now()
            },
        )
        publisher.publishEvent(
            OpenCardReply(
                phone = phone,
                language = language,
                key = "whatsapp.card.question",
                values =
                    mapOf(
                        "eventName" to view.eventName,
                        "when" to "",
                        "where" to (view.eventLocation?.let { "\n$it" } ?: ""),
                        "welcome" to (view.welcomeMessage?.let { "\n\n$it" } ?: ""),
                        "deadline" to "",
                    ),
                buttons =
                    listOf(
                        OpenCardButton(CardButton.id(ACTION_YES, code), "whatsapp.card.button.yes"),
                        OpenCardButton(CardButton.id(ACTION_MAYBE, code), "whatsapp.card.button.maybe"),
                        OpenCardButton(CardButton.id(ACTION_NO, code), "whatsapp.card.button.no"),
                    ),
                eventStart = view.eventStart,
                eventTimezone = view.eventTimezone,
                answerBy = view.closesAt,
                imageUrl = cardImageUrl(code),
            ),
        )
    }

    /** The card image the web app draws for [code], shown at the top of the first message (JIKU-194). */
    private fun cardImageUrl(code: String): String? =
        properties.cardImageBaseUrl
            .trimEnd('/')
            .takeIf { it.isNotEmpty() }
            ?.let { "$it/api/cards/$code" }

    /** "Coming": the place is held at once, then the companions are asked, or the ticket sent when none are allowed. */
    private fun holdYes(
        message: OpenCardInbound,
        code: String,
    ) = inInvitation(message.from, code) { eventId, language ->
        val view = service.publicView(eventId)
        val answered = respond(message, eventId, language, OpenAnswer.YES, 0) ?: return@inInvitation
        if (view.maxCompanions == 0) return@inInvitation sendTicket(message, view.eventName, language, answered)
        threads.save(
            threads.findById(message.from).orElse(OpenCardThread(message.from, code)).apply {
                this.code = code
                updatedAt =
                    Instant.now()
            },
        )
        val buttons =
            listOfNotNull(
                OpenCardButton(CardButton.id(ACTION_COMPANIONS + 0, code), "whatsapp.card.button.alone"),
                OpenCardButton(CardButton.id(ACTION_COMPANIONS + 1, code), "whatsapp.card.button.plusOne"),
                OpenCardButton(
                    CardButton.id(ACTION_COMPANIONS + 2, code),
                    "whatsapp.card.button.plusTwo",
                ).takeIf { view.maxCompanions >= 2 },
            )
        val key = if (view.maxCompanions > 2) "whatsapp.card.companionsMore" else "whatsapp.card.companions"
        reply(message.from, language, key, mapOf("max" to view.maxCompanions.toString()), buttons)
    }

    private fun answerYes(
        message: OpenCardInbound,
        code: String,
        companions: Int,
    ) = inInvitation(message.from, code) { eventId, language ->
        val view = service.publicView(eventId)
        if (companions < 0 || companions > view.maxCompanions) {
            return@inInvitation reply(message.from, language, "whatsapp.card.tooMany", mapOf("max" to view.maxCompanions.toString()))
        }
        val answered = respond(message, eventId, language, OpenAnswer.YES, companions) ?: return@inInvitation
        sendTicket(message, view.eventName, language, answered)
    }

    private fun answerOther(
        message: OpenCardInbound,
        code: String,
        answer: OpenAnswer,
    ) = inInvitation(message.from, code) { eventId, language ->
        val view = service.publicView(eventId)
        respond(message, eventId, language, answer, 0) ?: return@inInvitation
        val key = if (answer == OpenAnswer.MAYBE) "whatsapp.card.maybe" else "whatsapp.card.no"
        reply(message.from, language, key, mapOf("eventName" to view.eventName, "code" to view.code))
    }

    /** Records the answer; on a refusal, tells the person why and returns null. */
    private fun respond(
        message: OpenCardInbound,
        eventId: UUID,
        language: String,
        answer: OpenAnswer,
        companions: Int,
    ): OpenResponseView? {
        val name = message.profileName?.takeIf { it.isNotBlank() }?.take(NAME_LENGTH) ?: "+${message.from}"
        return try {
            service.respond(eventId, OpenResponseRequest(name, message.from, answer, companions), OpenResponseChannel.WHATSAPP)
        } catch (ex: ResponseStatusException) {
            val view = service.publicView(eventId)
            val values = mapOf("eventName" to view.eventName, "max" to view.maxCompanions.toString())
            when (ex.statusCode) {
                HttpStatus.PAYMENT_REQUIRED -> reply(message.from, language, "whatsapp.card.limit", values)
                HttpStatus.FORBIDDEN -> reply(message.from, language, "whatsapp.card.removed", values)
                HttpStatus.BAD_REQUEST -> reply(message.from, language, "whatsapp.card.tooMany", values)
                else ->
                    if (view.accepting) {
                        reply(
                            message.from,
                            language,
                            "whatsapp.card.full",
                            values,
                        )
                    } else {
                        closed(message.from, language, view)
                    }
            }
            null
        }
    }

    private fun sendTicket(
        message: OpenCardInbound,
        eventName: String,
        language: String,
        answered: OpenResponseView,
    ) {
        val token = answered.ticketToken ?: return
        val links = ticketNotices.links(token, language)
        publisher.publishEvent(
            OpenCardReply(
                phone = message.from,
                language = language,
                key = if (answered.companions == 0) "whatsapp.card.yes" else "whatsapp.card.yesWith",
                values =
                    mapOf(
                        "name" to (message.profileName?.substringBefore(' ')?.takeIf { it.isNotBlank() } ?: ""),
                        "eventName" to eventName,
                        "companions" to answered.companions.toString(),
                        "ticketUrl" to links.ticketUrl,
                    ),
                imageUrl = links.qrImageUrl,
            ),
        )
    }

    private fun closed(
        phone: String,
        language: String,
        view: PublicOpenInvitationView,
    ) {
        val key = if (view.closedReason == OpenClosedReason.FULL) "whatsapp.card.full" else "whatsapp.card.closed"
        reply(phone, language, key, mapOf("eventName" to view.eventName))
    }

    /** Runs [block] under the invitation's tenant with the organization's language; an unknown code is told so. */
    private fun inInvitation(
        phone: String,
        code: String,
        block: (UUID, String) -> Unit,
    ) {
        try {
            service.route(code) { eventId ->
                val tenant = TenantContext.get()?.let { tenants.findTenant(UUID.fromString(it)) }
                block(eventId, MessageLanguage.forCountry(tenant?.country))
            }
        } catch (ex: ResponseStatusException) {
            if (ex.statusCode != HttpStatus.NOT_FOUND) throw ex
            reply(phone, MessageLanguage.FRENCH, "whatsapp.card.unknown")
        }
    }

    private fun reply(
        phone: String,
        language: String,
        key: String,
        values: Map<String, String> = emptyMap(),
        buttons: List<OpenCardButton> = emptyList(),
    ) {
        publisher.publishEvent(OpenCardReply(phone = phone, language = language, key = key, values = values, buttons = buttons))
    }

    /** A card button's id: `OI:<action>:<code>`, what the webhook receives back when it is tapped. */
    data class CardButton(
        val action: String,
        val code: String,
    ) {
        companion object {
            private const val PREFIX = "OI:"

            fun id(
                action: String,
                code: String,
            ): String = "$PREFIX$action:$code"

            fun parse(id: String): CardButton? {
                if (!id.startsWith(PREFIX)) return null
                val parts = id.removePrefix(PREFIX).split(':')
                return if (parts.size == 2) CardButton(parts[0], parts[1]) else null
            }
        }
    }

    private companion object {
        const val ACTION_YES = "Y"
        const val ACTION_MAYBE = "M"
        const val ACTION_NO = "N"
        const val ACTION_COMPANIONS = "C"
        const val NAME_LENGTH = 120

        /** An invitation code: the unambiguous alphabet of [com.jiku.shared.RandomCode], eight characters. */
        val CODE = Regex("\\b[A-HJKMNP-Z2-9]{8}\\b")
    }
}
