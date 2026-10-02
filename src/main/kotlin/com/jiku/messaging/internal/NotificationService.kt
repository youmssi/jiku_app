package com.jiku.messaging.internal

import com.jiku.shared.ClientCalled
import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.MessageLanguage
import com.jiku.shared.PhoneCodeRequested
import com.jiku.shared.ReminderAllowanceGate
import com.jiku.shared.ReminderChannel
import com.jiku.shared.ReminderDue
import com.jiku.shared.TenantContext
import com.jiku.shared.TicketConfirmedNotice
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.util.UUID

/** An invitation sent by WhatsApp that the guest may answer in the chat (JIKU-143). */
data class WhatsAppThreadStart(
    val invitationId: UUID,
    val tenantId: String,
    val language: String,
)

/** The outcome of attempting to deliver a notification. */
data class DeliveryOutcome(
    val delivered: Boolean,
    val attempts: Int,
    val error: String?,
    /** See [com.jiku.shared.InvitationDeliveryResult.queued]. */
    val queued: Boolean = false,
)

/**
 * Centralized delivery for outbound notifications: renders the channel's template
 * (incorporating tenant branding carried on the event), sends through the provider
 * adapter with bounded retry, and records every attempt in the audit log. This is
 * the single place retry and failure handling live — no other module sends.
 */
@Service
class NotificationService(
    private val emailRenderer: EmailTemplateRenderer,
    private val whatsAppRenderer: WhatsAppTemplateRenderer,
    private val providers: MessagingProviderResolver,
    private val sendProperties: NotificationSendProperties,
    private val logs: NotificationLogRepository,
    private val contentGuard: WhatsAppContentGuard,
    private val conversationCounter: WhatsAppConversationCounter,
    private val costTracker: WhatsAppCostTracker,
    private val smsSender: SmsSender,
    private val catalog: MessageCatalog,
    private val threads: WhatsAppThreadRepository,
    private val optOuts: WhatsAppOptOutRepository,
    private val reminderAllowance: ReminderAllowanceGate,
    private val emailPause: TenantEmailPause,
    private val templateCalls: WhatsAppTemplateCalls,
    private val deliveries: WhatsAppDeliveryStatusService,
    private val whatsAppPause: TenantWhatsAppPause,
    private val platformLimits: WhatsAppPlatformLimits,
) {
    private val log = LoggerFactory.getLogger(NotificationService::class.java)

    fun deliverInvitation(event: GuestInvitedEvent): DeliveryOutcome =
        deliverWithRetry(sendAction(event)) { status, attempt, error ->
            record(event.invitationId, event.channel, event.recipient, status, attempt, error)
        }

    fun deliverCancellation(notice: EventCancellationNotice): DeliveryOutcome =
        deliverWithRetry(cancellationSendAction(notice)) { status, attempt, error ->
            record(notice.invitationId, notice.channel, notice.recipient, status, attempt, error)
        }

    /**
     * A confirmed guest's ticket (JIKU-129): by email with a calendar invite
     * attached when the event has a date, or in the guest's WhatsApp chat with
     * its QR code when they answered there (JIKU-143). Logged against the guest.
     */
    fun deliverTicketConfirmation(notice: TicketConfirmedNotice): DeliveryOutcome {
        val send =
            if (notice.channel == GuestInvitedEvent.CHANNEL_WHATSAPP) ticketWhatsApp(notice, null) else ticketEmail(notice)
        return deliverWithRetry(send) { status, attempt, error ->
            record(notice.guestId, notice.channel, notice.recipient, status, attempt, error)
        }
    }

    /** The ticket in WhatsApp, its QR code as the image; [invitationId] records the thread when it opens one. */
    private fun ticketWhatsApp(
        notice: TicketConfirmedNotice,
        invitationId: UUID?,
    ): () -> Unit {
        val ticket =
            WhatsAppInvitation(
                recipientPhone = notice.recipient,
                recipientName = notice.recipientName,
                eventName = notice.eventName,
                eventWhen = notice.eventStart?.let { MessageLanguage.formatEventStart(it, notice.eventTimezone, notice.language) },
                organizerName = notice.organizerName,
                invitationUrl = notice.ticketUrl,
            )
        val text = whatsAppRenderer.renderTicket(ticket, notice.language)
        val thread = invitationId?.let { WhatsAppThreadStart(it, notice.tenantId, notice.language) }
        return whatsApp(
            WhatsAppMessage(
                to = notice.recipient,
                body = text,
                imageUrl = notice.qrImageUrl,
                template = notice.qrImageUrl?.let { templateCalls.ticket(ticket, notice.language) },
            ),
            invitationId ?: notice.guestId,
            notice.eventId,
            thread,
        )
    }

    /** The ticket email, with the calendar invite attached when the event has a date. */
    private fun ticketEmail(notice: TicketConfirmedNotice): () -> Unit {
        val entry =
            notice.eventStart?.let { start ->
                CalendarEntry(
                    uid = "${notice.guestId}@jiku",
                    title = notice.eventName,
                    start = start,
                    end = notice.eventEnd,
                    location = notice.eventLocation,
                    description = "${notice.organizerName} · ${notice.ticketUrl}",
                    url = notice.ticketUrl,
                )
            }
        val rendered = emailRenderer.renderTicketConfirmed(notice, entry?.let { EventCalendar.googleLink(it) })
        return email(
            EmailMessage(
                to = notice.recipient,
                toName = notice.recipientName,
                subject = rendered.subject,
                htmlBody = rendered.html,
                attachments =
                    listOfNotNull(
                        entry?.let { EmailAttachment(CALENDAR_FILE, CALENDAR_TYPE, EventCalendar.ics(it)) },
                    ),
            ),
        )
    }

    /**
     * Reminder de rendez-vous (JIKU-89), par le canal choisi pour le service :
     * le parcours de réservation ne capture que le téléphone. WhatsApp passe par le
     * même chemin que l'invitation — classification du contenu, garde-fous de
     * coût, suivi du coût et journal d'audit. Avec WHATSAPP_OR_SMS, un rappel que
     * WhatsApp ne peut pas délivrer part par SMS (JIKU-112) : un rappel manqué est
     * un client absent. Un rappel non délivré ne remonte jamais à la réservation.
     * Envoyé depuis le numéro de l'organisation, Meta le lui facture : il ne
     * compte pas dans les rappels mensuels d'une offre gratuite (ADR 105).
     */
    fun deliverAppointmentReminder(due: ReminderDue): DeliveryOutcome {
        val ownNumber = providers.whatsApp().tenantOverride
        val language = catalog.language(due.tenantId)
        val reminder = reminder(due, language)
        return deliverToPhone(
            due.reminderId,
            due.clientPhone,
            due.channel,
            whatsAppRenderer.renderAppointmentReminder(reminder, language),
            templateCalls.reminder(reminder, language),
            whatsAppAllowed = ownNumber || reminderAllowance.canSendWhatsAppReminder(due.tenantId),
            onWhatsAppSent = { if (!ownNumber) reminderAllowance.recordWhatsAppReminder(due.tenantId) },
        )
    }

    /** "It's your turn" for a client just called in the line (JIKU-114), by the service's channel. */
    fun deliverClientCalled(called: ClientCalled): DeliveryOutcome {
        val language = catalog.language(called.tenantId)
        return deliverToPhone(
            called.ticketId,
            called.clientPhone,
            called.channel,
            whatsAppRenderer.renderClientCalled(called.clientName.orEmpty(), called.counter, language),
            templateCalls.clientCalled(called.clientName, called.counter, language),
        )
    }

    /** Sends an organization its phone verification code, by SMS so any number receives it. */
    fun deliverPhoneCode(requested: PhoneCodeRequested): DeliveryOutcome =
        deliverToPhone(
            UUID.randomUUID(),
            requested.phone,
            ReminderChannel.SMS,
            whatsAppRenderer.renderPhoneCode(requested.code, catalog.language(requested.tenantId)),
            template = null,
        )

    /**
     * Sends [text] to a client's phone by [channel]. With WHATSAPP_OR_SMS, an SMS
     * takes over whenever WhatsApp cannot deliver (JIKU-112): the fallback is
     * decided here, never by a provider, so a provider switch cannot change it.
     * When [whatsAppAllowed] is off (a free plan's monthly reminders are used,
     * ADR 105), WhatsApp is not tried and the channel's SMS fallback, if any,
     * takes over.
     */
    private fun deliverToPhone(
        referenceId: UUID,
        phone: String,
        channel: ReminderChannel,
        text: String,
        template: WhatsAppTemplateCall?,
        whatsAppAllowed: Boolean = true,
        onWhatsAppSent: () -> Unit = {},
    ): DeliveryOutcome {
        val byWhatsApp = {
            if (whatsAppAllowed) {
                deliverLogged(
                    referenceId,
                    ReminderChannel.WHATSAPP,
                    phone,
                    whatsApp(
                        WhatsAppMessage(phone, text, template = template),
                        referenceId,
                        null,
                        smsFallback = text.takeIf { channel == ReminderChannel.WHATSAPP_OR_SMS },
                    ),
                ).also { if (it.delivered) onWhatsAppSent() }
            } else {
                record(referenceId, ReminderChannel.WHATSAPP.name, phone, NotificationLog.STATUS_FAILED, 0, FREE_REMINDERS_USED)
                DeliveryOutcome(delivered = false, attempts = 0, error = FREE_REMINDERS_USED)
            }
        }
        val bySms = { deliverLogged(referenceId, ReminderChannel.SMS, phone, sms(phone, text)) }
        return when (channel) {
            ReminderChannel.WHATSAPP -> byWhatsApp()
            ReminderChannel.SMS -> bySms()
            ReminderChannel.WHATSAPP_OR_SMS -> byWhatsApp().takeIf { it.delivered } ?: bySms()
            ReminderChannel.NONE -> throw IllegalArgumentException("Message $referenceId has no channel")
        }
    }

    private fun deliverLogged(
        referenceId: UUID,
        channel: ReminderChannel,
        phone: String,
        send: () -> Unit,
    ): DeliveryOutcome =
        deliverWithRetry(send) { status, attempt, error ->
            record(referenceId, channel.name, phone, status, attempt, error)
        }

    private fun reminder(
        due: ReminderDue,
        language: String,
    ) = WhatsAppReminder(
        recipientPhone = due.clientPhone,
        recipientName = due.clientName.orEmpty(),
        appointmentWhen = catalog.formatDate(language, "date.reminder", due.startsAt, ZoneId.of(due.serviceTimezone)),
        professionalName = due.professionalName,
    )

    /**
     * One WhatsApp send with its guardrails: the recipient's STOP (JIKU-143),
     * content class, conversation budget, cost record. When [thread] is given,
     * the send is remembered so the guest's replies find their invitation.
     */
    private fun whatsApp(
        message: WhatsAppMessage,
        referenceId: UUID,
        eventId: UUID?,
        thread: WhatsAppThreadStart? = null,
        smsFallback: String? = null,
        consentAttested: Boolean = true,
    ): () -> Unit =
        {
            val phone = whatsAppDigits(message.to)
            if (optOuts.existsById(phone)) {
                throw WhatsAppOptedOutException("${message.to} wrote STOP: nothing more is sent to it by WhatsApp")
            }
            val resolved = providers.whatsApp()
            val category = contentGuard.classify(message.body)
            contentGuard.assertAllowed(category)
            if (!resolved.tenantOverride) {
                if (!consentAttested) {
                    throw WhatsAppConsentMissingException(
                        "The organizer has not confirmed that this guest agreed to hear from it: " +
                            "confirm consent for the event's guests, or invite them by email",
                    )
                }
                whatsAppPause.assertNotPaused(TenantContext.get())
                platformLimits.assertWithinLimits()
            }
            conversationCounter.assertWithinBudget(resolved.tenantOverride)
            val wamid = resolved.sender.send(message)
            wamid?.let { deliveries.record(it, referenceId, message.to, resolved.tenantOverride, thread != null, smsFallback) }
            costTracker.record(referenceId, eventId, resolved.tenantOverride, category)
            thread?.let { threads.save(WhatsAppThread(it.invitationId, it.tenantId, phone, it.language)) }
        }

    private fun email(message: EmailMessage): () -> Unit =
        {
            val resolved = providers.email()
            if (!resolved.tenantOverride) emailPause.assertNotPaused(TenantContext.get())
            resolved.sender.send(resolved.from, message)
        }

    private fun sms(
        to: String,
        text: String,
    ): () -> Unit =
        {
            val body = SmsText.toGsm7(text)
            val segments = SmsText.segments(body)
            if (segments > 1) log.info("SMS takes {} segments ({} characters)", segments, body.length)
            smsSender.send(SmsMessage(to = to, body = body))
        }

    private fun deliverWithRetry(
        send: () -> Unit,
        record: (status: String, attempt: Int, error: String?) -> Unit,
    ): DeliveryOutcome {
        var lastError: String? = null
        for (attempt in 1..sendProperties.maxAttempts) {
            try {
                send()
                record(NotificationLog.STATUS_SENT, attempt, null)
                return DeliveryOutcome(delivered = true, attempts = attempt, error = null)
            } catch (ex: WhatsAppQuotaExceededException) {
                // Not a delivery failure — capacity will free up within the 24h
                // window, so this is queued for the sweep to retry, never looped
                // in a tight retry that cannot possibly help within milliseconds.
                record(NotificationLog.STATUS_QUEUED, attempt, ex.message)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = null, queued = true)
            } catch (ex: WhatsAppUnavailableException) {
                // A paused template, a blocked account or a Meta rate limit
                // clears in hours, not seconds (JIKU-209): queued like a quota,
                // and a reminder that allows SMS goes by SMS.
                record(NotificationLog.STATUS_QUEUED, attempt, ex.message)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = null, queued = true)
            } catch (ex: EmailQuotaExceededException) {
                // Same reasoning as the WhatsApp quota case above, for the email
                // routing daily caps (JIKU-62) — queued until tomorrow's reset.
                record(NotificationLog.STATUS_QUEUED, attempt, ex.message)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = null, queued = true)
            } catch (ex: WhatsAppOptedOutException) {
                // The recipient asked for silence: retrying would only ask again.
                lastError = ex.message
                record(NotificationLog.STATUS_FAILED, attempt, lastError)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = lastError)
            } catch (ex: TenantEmailPausedException) {
                // Retrying within seconds cannot lower the bounce rate.
                lastError = ex.message
                record(NotificationLog.STATUS_FAILED, attempt, lastError)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = lastError)
            } catch (ex: WhatsAppConsentMissingException) {
                lastError = ex.message
                record(NotificationLog.STATUS_FAILED, attempt, lastError)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = lastError)
            } catch (ex: TenantWhatsAppPausedException) {
                // Retrying cannot lower the organization's STOP or failure rate (JIKU-211).
                lastError = ex.message
                record(NotificationLog.STATUS_FAILED, attempt, lastError)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = lastError)
            } catch (ex: WhatsAppContentPolicyException) {
                // A human decision is required (fix the content or enable the
                // override) — retrying automatically would just repeat the block.
                lastError = ex.message
                record(NotificationLog.STATUS_FAILED, attempt, lastError)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = lastError)
            } catch (ex: Exception) {
                lastError = ex.message ?: ex.javaClass.simpleName
                record(NotificationLog.STATUS_FAILED, attempt, lastError)
            }
        }
        return DeliveryOutcome(delivered = false, attempts = sendProperties.maxAttempts, error = lastError)
    }

    private fun sendAction(event: GuestInvitedEvent): () -> Unit {
        val ticket = event.ticket
        return if (ticket == null) invitationAction(event) else directTicketAction(event, ticket)
    }

    /** The ticket itself, for an event that sends tickets directly (ADR 105). */
    private fun directTicketAction(
        event: GuestInvitedEvent,
        ticket: TicketConfirmedNotice,
    ): () -> Unit =
        when (event.channel) {
            GuestInvitedEvent.CHANNEL_EMAIL -> ticketEmail(ticket)
            GuestInvitedEvent.CHANNEL_WHATSAPP -> ticketWhatsApp(ticket, event.invitationId)

            else -> throw IllegalArgumentException("Unsupported channel: ${event.channel}")
        }

    private fun invitationAction(event: GuestInvitedEvent): () -> Unit =
        when (event.channel) {
            GuestInvitedEvent.CHANNEL_EMAIL -> {
                val rendered =
                    emailRenderer.renderInvitation(
                        InvitationEmail(
                            recipientEmail = event.recipient,
                            recipientName = event.recipientName,
                            eventName = event.eventName,
                            eventWhen = event.eventWhen,
                            eventLocation = event.eventLocation,
                            organizerName = event.organizerName,
                            primaryColor = event.primaryColor,
                            logoUrl = event.logoUrl,
                            invitationUrl = event.invitationUrl,
                        ),
                        event.language,
                    )
                val message =
                    EmailMessage(
                        to = event.recipient,
                        toName = event.recipientName,
                        subject = rendered.subject,
                        htmlBody = rendered.html,
                    )
                email(message)
            }

            GuestInvitedEvent.CHANNEL_WHATSAPP -> {
                val invitation =
                    WhatsAppInvitation(
                        recipientPhone = event.recipient,
                        recipientName = event.recipientName,
                        eventName = event.eventName,
                        eventWhen = event.eventWhen,
                        organizerName = event.organizerName,
                        invitationUrl = event.invitationUrl,
                    )
                val text = whatsAppRenderer.renderInvitation(invitation, event.language)
                val buttons = if (event.interactive) replyButtons(event.invitationId, event.language) else emptyList()
                whatsApp(
                    WhatsAppMessage(
                        to = event.recipient,
                        body = text,
                        buttons = buttons,
                        template = templateCalls.invitation(invitation, event.language, withButtons = buttons.isNotEmpty()),
                    ),
                    event.invitationId,
                    event.eventId,
                    WhatsAppThreadStart(event.invitationId, event.tenantId, event.language),
                    consentAttested = event.consentAttested,
                )
            }

            else -> throw IllegalArgumentException("Unsupported channel: ${event.channel}")
        }

    /** Accept and decline, each carrying the invitation it answers (JIKU-143). */
    private fun replyButtons(
        invitationId: UUID,
        language: String,
    ): List<WhatsAppButton> =
        listOf(
            WhatsAppButton(WhatsAppReplyPayload.accept(invitationId), catalog.text(language, "whatsapp.button.accept")),
            WhatsAppButton(WhatsAppReplyPayload.decline(invitationId), catalog.text(language, "whatsapp.button.decline")),
        )

    private fun cancellationSendAction(notice: EventCancellationNotice): () -> Unit =
        when (notice.channel) {
            GuestInvitedEvent.CHANNEL_EMAIL -> {
                val rendered =
                    emailRenderer.renderCancellation(
                        CancellationEmail(
                            recipientEmail = notice.recipient,
                            recipientName = notice.recipientName,
                            eventName = notice.eventName,
                            eventWhen = notice.eventWhen,
                            eventLocation = notice.eventLocation,
                            organizerName = notice.organizerName,
                            primaryColor = notice.primaryColor,
                            logoUrl = notice.logoUrl,
                        ),
                        notice.language,
                    )
                val message =
                    EmailMessage(
                        to = notice.recipient,
                        toName = notice.recipientName,
                        subject = rendered.subject,
                        htmlBody = rendered.html,
                    )
                email(message)
            }

            GuestInvitedEvent.CHANNEL_WHATSAPP -> {
                val cancellation =
                    WhatsAppCancellation(
                        recipientPhone = notice.recipient,
                        recipientName = notice.recipientName,
                        eventName = notice.eventName,
                        eventWhen = notice.eventWhen,
                        organizerName = notice.organizerName,
                    )
                val text = whatsAppRenderer.renderCancellation(cancellation, notice.language)
                whatsApp(
                    WhatsAppMessage(notice.recipient, text, template = templateCalls.cancellation(cancellation, notice.language)),
                    notice.invitationId,
                    notice.eventId,
                )
            }

            else -> throw IllegalArgumentException("Unsupported channel: ${notice.channel}")
        }

    private fun record(
        referenceId: UUID,
        channel: String,
        recipient: String,
        status: String,
        attempt: Int,
        error: String?,
    ) {
        logs.save(
            NotificationLog(
                referenceId = referenceId,
                channel = channel,
                recipient = recipient,
                status = status,
                attempt = attempt,
                error = error?.take(500),
            ),
        )
    }

    private companion object {
        const val FREE_REMINDERS_USED = "The free plan's WhatsApp reminders for this month are used"
        const val CALENDAR_FILE = "invitation.ics"
        const val CALENDAR_TYPE = "text/calendar; charset=utf-8; method=PUBLISH"
    }
}
