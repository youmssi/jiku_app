package com.jiku.messaging.internal

import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.ReminderDue
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
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
     * Reminder de rendez-vous (JIKU-89), canal WhatsApp uniquement : le parcours
     * de réservation ne capture que le téléphone. Passe par le même chemin que
     * l'invitation — classification du contenu, garde-fous de coût, suivi du coût
     * et journal d'audit — sans contournement. Un rappel non délivré ne remonte
     * jamais à la réservation.
     */
    fun deliverAppointmentReminder(due: ReminderDue): DeliveryOutcome =
        deliverWithRetry(reminderSendAction(due)) { status, attempt, error ->
            record(due.reminderId, GuestInvitedEvent.CHANNEL_WHATSAPP, due.clientPhone, status, attempt, error)
        }

    private fun reminderSendAction(due: ReminderDue): () -> Unit {
        val whenText =
            due.startsAt
                .atZone(ZoneId.of(due.serviceTimezone))
                .format(REMINDER_WHEN_FORMAT)
        val text =
            whatsAppRenderer.renderAppointmentReminder(
                WhatsAppReminder(
                    recipientPhone = due.clientPhone,
                    recipientName = due.clientName.orEmpty(),
                    appointmentWhen = whenText,
                    professionalName = due.professionalName,
                ),
            )
        return {
            val resolved = providers.whatsApp()
            val category = contentGuard.classify(text)
            contentGuard.assertAllowed(category)
            conversationCounter.assertWithinBudget(resolved.tenantOverride)
            resolved.sender.send(WhatsAppMessage(to = due.clientPhone, body = text))
            costTracker.record(due.reminderId, null, resolved.tenantOverride, category)
        }
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
                val html =
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
                    )
                val message =
                    EmailMessage(
                        to = event.recipient,
                        toName = event.recipientName,
                        subject = "You're invited to ${event.eventName}",
                        htmlBody = html,
                    )
                (
                    {
                        val resolved = providers.email()
                        resolved.sender.send(resolved.from, message)
                    }
                )
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
                    )
                (
                    {
                        val resolved = providers.whatsApp()
                        val category = contentGuard.classify(text)
                        contentGuard.assertAllowed(category)
                        conversationCounter.assertWithinBudget(resolved.tenantOverride)
                        resolved.sender.send(WhatsAppMessage(to = event.recipient, body = text))
                        costTracker.record(event.invitationId, event.eventId, resolved.tenantOverride, category)
                    }
                )
            }

            else -> throw IllegalArgumentException("Unsupported channel: ${event.channel}")
        }

    private fun cancellationSendAction(notice: EventCancellationNotice): () -> Unit =
        when (notice.channel) {
            GuestInvitedEvent.CHANNEL_EMAIL -> {
                val html =
                    emailRenderer.renderCancellation(
                        CancellationEmail(
                            recipientEmail = notice.recipient,
                            recipientName = notice.recipientName,
                            eventName = notice.eventName,
                            eventWhen = notice.eventWhen,
                            eventLocation = notice.eventLocation,
                            organizerName = notice.organizerName,
                            logoUrl = notice.logoUrl,
                        ),
                    )
                val message =
                    EmailMessage(
                        to = notice.recipient,
                        toName = notice.recipientName,
                        subject = "${notice.eventName} has been cancelled",
                        htmlBody = html,
                    )
                (
                    {
                        val resolved = providers.email()
                        resolved.sender.send(resolved.from, message)
                    }
                )
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
                    )
                (
                    {
                        val resolved = providers.whatsApp()
                        val category = contentGuard.classify(text)
                        contentGuard.assertAllowed(category)
                        conversationCounter.assertWithinBudget(resolved.tenantOverride)
                        resolved.sender.send(WhatsAppMessage(to = notice.recipient, body = text))
                        costTracker.record(notice.invitationId, notice.eventId, resolved.tenantOverride, category)
                    }
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

    companion object {
        private val REMINDER_WHEN_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d 'at' HH:mm", Locale.ENGLISH)
    }
}
