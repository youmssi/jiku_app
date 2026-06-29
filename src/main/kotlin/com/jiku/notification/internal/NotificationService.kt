package com.jiku.notification.internal

import com.jiku.shared.GuestInvitedEvent
import org.springframework.stereotype.Service

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
    private val emailSender: EmailSender,
    private val whatsAppRenderer: WhatsAppTemplateRenderer,
    private val whatsAppSender: WhatsAppSender,
    private val emailProperties: NotificationEmailProperties,
    private val sendProperties: NotificationSendProperties,
    private val logs: NotificationLogRepository,
) {
    fun deliverInvitation(event: GuestInvitedEvent): DeliveryOutcome {
        val send = sendAction(event)
        var lastError: String? = null
        for (attempt in 1..sendProperties.maxAttempts) {
            try {
                send()
                record(event, NotificationLog.STATUS_SENT, attempt, null)
                return DeliveryOutcome(delivered = true, attempts = attempt, error = null)
            } catch (ex: Exception) {
                lastError = ex.message ?: ex.javaClass.simpleName
                record(event, NotificationLog.STATUS_FAILED, attempt, lastError)
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
                ({ emailSender.send(emailProperties.from, message) })
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
                ({ whatsAppSender.send(WhatsAppMessage(to = event.recipient, body = text)) })
            }

            else -> throw IllegalArgumentException("Unsupported channel: ${event.channel}")
        }

    private fun record(
        event: GuestInvitedEvent,
        status: String,
        attempt: Int,
        error: String?,
    ) {
        logs.save(
            NotificationLog(
                referenceId = event.invitationId,
                channel = event.channel,
                recipient = event.recipient,
                status = status,
                attempt = attempt,
                error = error?.take(500),
            ),
        )
    }
}
