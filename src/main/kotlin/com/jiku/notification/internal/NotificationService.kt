package com.jiku.notification.internal

import com.jiku.shared.EventCancellationNotice
import com.jiku.shared.GuestInvitedEvent
import org.springframework.stereotype.Service
import java.util.UUID

/** The outcome of attempting to deliver a notification. */
data class DeliveryOutcome(
    val delivered: Boolean,
    val attempts: Int,
    val error: String?,
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
) {
    fun deliverInvitation(event: GuestInvitedEvent): DeliveryOutcome =
        deliverWithRetry(sendAction(event)) { status, attempt, error ->
            record(event.invitationId, event.channel, event.recipient, status, attempt, error)
        }

    fun deliverCancellation(notice: EventCancellationNotice): DeliveryOutcome =
        deliverWithRetry(cancellationSendAction(notice)) { status, attempt, error ->
            record(notice.invitationId, notice.channel, notice.recipient, status, attempt, error)
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
                ({ providers.whatsApp().sender.send(WhatsAppMessage(to = event.recipient, body = text)) })
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
                ({ providers.whatsApp().sender.send(WhatsAppMessage(to = notice.recipient, body = text)) })
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
