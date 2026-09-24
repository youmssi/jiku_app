package com.jiku.messaging.internal

import com.jiku.shared.ClientCalled
import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.ReminderChannel
import com.jiku.shared.ReminderDue
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.util.UUID

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
) {
    fun deliverInvitation(event: GuestInvitedEvent): DeliveryOutcome =
        deliverWithRetry(sendAction(event)) { status, attempt, error ->
            record(event.invitationId, event.channel, event.recipient, status, attempt, error)
        }

    fun deliverCancellation(notice: EventCancellationNotice): DeliveryOutcome =
        deliverWithRetry(cancellationSendAction(notice)) { status, attempt, error ->
            record(notice.invitationId, notice.channel, notice.recipient, status, attempt, error)
        }

    /**
     * Reminder de rendez-vous (JIKU-89), par le canal choisi pour le service :
     * le parcours de réservation ne capture que le téléphone. WhatsApp passe par le
     * même chemin que l'invitation — classification du contenu, garde-fous de
     * coût, suivi du coût et journal d'audit. Avec WHATSAPP_OR_SMS, un rappel que
     * WhatsApp ne peut pas délivrer part par SMS (JIKU-112) : un rappel manqué est
     * un client absent. Un rappel non délivré ne remonte jamais à la réservation.
     */
    fun deliverAppointmentReminder(due: ReminderDue): DeliveryOutcome =
        deliverToPhone(due.reminderId, due.clientPhone, due.channel, reminderText(due))

    /** "It's your turn" for a client just called in the line (JIKU-114), by the service's channel. */
    fun deliverClientCalled(called: ClientCalled): DeliveryOutcome =
        deliverToPhone(
            called.ticketId,
            called.clientPhone,
            called.channel,
            whatsAppRenderer.renderClientCalled(called.clientName.orEmpty(), called.counter, catalog.language(called.tenantId)),
        )

    /**
     * Sends [text] to a client's phone by [channel]. With WHATSAPP_OR_SMS, an SMS
     * takes over whenever WhatsApp cannot deliver (JIKU-112): the fallback is
     * decided here, never by a provider, so a provider switch cannot change it.
     */
    private fun deliverToPhone(
        referenceId: UUID,
        phone: String,
        channel: ReminderChannel,
        text: String,
    ): DeliveryOutcome {
        val byWhatsApp = { deliverLogged(referenceId, ReminderChannel.WHATSAPP, phone, whatsApp(phone, text, referenceId, null)) }
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

    private fun reminderText(due: ReminderDue): String {
        val language = catalog.language(due.tenantId)
        return whatsAppRenderer.renderAppointmentReminder(
            WhatsAppReminder(
                recipientPhone = due.clientPhone,
                recipientName = due.clientName.orEmpty(),
                appointmentWhen = catalog.formatDate(language, "date.reminder", due.startsAt, ZoneId.of(due.serviceTimezone)),
                professionalName = due.professionalName,
            ),
            language,
        )
    }

    /** One WhatsApp send with its guardrails: content class, conversation budget, cost record. */
    private fun whatsApp(
        to: String,
        text: String,
        referenceId: UUID,
        eventId: UUID?,
    ): () -> Unit =
        {
            val resolved = providers.whatsApp()
            val category = contentGuard.classify(text)
            contentGuard.assertAllowed(category)
            conversationCounter.assertWithinBudget(resolved.tenantOverride)
            resolved.sender.send(WhatsAppMessage(to = to, body = text))
            costTracker.record(referenceId, eventId, resolved.tenantOverride, category)
        }

    private fun email(message: EmailMessage): () -> Unit =
        {
            val resolved = providers.email()
            resolved.sender.send(resolved.from, message)
        }

    private fun sms(
        to: String,
        text: String,
    ): () -> Unit = { smsSender.send(SmsMessage(to = to, body = text)) }

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
            } catch (ex: EmailQuotaExceededException) {
                // Same reasoning as the WhatsApp quota case above, for the email
                // routing daily caps (JIKU-62) — queued until tomorrow's reset.
                record(NotificationLog.STATUS_QUEUED, attempt, ex.message)
                return DeliveryOutcome(delivered = false, attempts = attempt, error = null, queued = true)
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

    private fun sendAction(event: GuestInvitedEvent): () -> Unit =
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
                val text =
                    whatsAppRenderer.renderInvitation(
                        WhatsAppInvitation(
                            recipientPhone = event.recipient,
                            recipientName = event.recipientName,
                            eventName = event.eventName,
                            eventWhen = event.eventWhen,
                            organizerName = event.organizerName,
                            invitationUrl = event.invitationUrl,
                        ),
                        event.language,
                    )
                whatsApp(event.recipient, text, event.invitationId, event.eventId)
            }

            else -> throw IllegalArgumentException("Unsupported channel: ${event.channel}")
        }

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
                val text =
                    whatsAppRenderer.renderCancellation(
                        WhatsAppCancellation(
                            recipientPhone = notice.recipient,
                            recipientName = notice.recipientName,
                            eventName = notice.eventName,
                            eventWhen = notice.eventWhen,
                            organizerName = notice.organizerName,
                        ),
                        notice.language,
                    )
                whatsApp(notice.recipient, text, notice.invitationId, notice.eventId)
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
}
