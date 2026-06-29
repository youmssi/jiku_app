package com.jiku.invitation.internal

import com.jiku.event.EventModuleApi
import com.jiku.event.InvitationChannel
import com.jiku.notification.InvitationEmail
import com.jiku.notification.NotificationModuleApi
import com.jiku.notification.WhatsAppInvitation
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Delivers a single invitation on its channel (email or WhatsApp), resolving the
 * guest, event and tenant branding, building the signed invitation link, and
 * sending with bounded retry. Runs in its own transaction.
 */
@Component
class InvitationDispatchWorker(
    private val invitations: InvitationRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val tokenService: InvitationTokenService,
    private val notifications: NotificationModuleApi,
    private val properties: InvitationSendProperties,
) {
    @Transactional
    fun process(invitationId: UUID) {
        val invitation = invitations.findById(invitationId).orElse(null) ?: return
        if (invitation.status == InvitationStatus.SENT) {
            return
        }
        val guest = guests.findById(invitation.guestId).orElse(null)
        if (guest == null) {
            fail(invitation, "Guest not found")
            return
        }

        val event = events.findEvent(invitation.eventId)
        val tenantId = TenantContext.get()
        val tenant = tenantId?.let { tenants.findTenant(UUID.fromString(it)) }
        val token = tokenService.issue(invitation.guestId, invitation.eventId, tenantId.orEmpty())
        val link = "${properties.appBaseUrl}/invitation/$token"
        val guestName = "${guest.firstName} ${guest.lastName}"
        val eventName = event?.name ?: "your event"
        val eventWhen = event?.startDateTime?.let { formatWhen(it, event.timezone) }
        val organizerName = tenant?.displayName ?: "Your organizer"

        val send: () -> Unit =
            when (invitation.channel) {
                InvitationChannel.EMAIL -> {
                    val recipient = guest.email
                    if (recipient == null) {
                        fail(invitation, "Guest has no email address")
                        return
                    }
                    val email =
                        InvitationEmail(
                            recipientEmail = recipient,
                            recipientName = guestName,
                            eventName = eventName,
                            eventWhen = eventWhen,
                            eventLocation = event?.location,
                            organizerName = organizerName,
                            primaryColor = tenant?.primaryColor ?: DEFAULT_COLOR,
                            logoUrl = tenant?.logoUrl,
                            invitationUrl = link,
                        )
                    val action: () -> Unit = { notifications.sendInvitationEmail(email) }
                    action
                }

                InvitationChannel.WHATSAPP -> {
                    val phone = guest.phoneNumber
                    if (phone == null || !E164.matches(phone)) {
                        fail(invitation, "Guest has no valid WhatsApp number")
                        return
                    }
                    val whatsApp =
                        WhatsAppInvitation(
                            recipientPhone = phone,
                            recipientName = guestName,
                            eventName = eventName,
                            eventWhen = eventWhen,
                            organizerName = organizerName,
                            invitationUrl = link,
                        )
                    val action: () -> Unit = { notifications.sendInvitationWhatsApp(whatsApp) }
                    action
                }
            }

        var lastError: String? = null
        repeat(properties.maxAttempts) {
            invitation.attempts += 1
            try {
                send()
                invitation.status = InvitationStatus.SENT
                invitation.sentAt = Instant.now()
                invitation.lastError = null
                invitations.save(invitation)
                return
            } catch (ex: Exception) {
                lastError = ex.message ?: ex.javaClass.simpleName
            }
        }
        fail(invitation, lastError)
    }

    private fun fail(
        invitation: Invitation,
        error: String?,
    ) {
        invitation.status = InvitationStatus.FAILED
        invitation.lastError = error
        invitations.save(invitation)
    }

    private fun formatWhen(
        instant: Instant,
        timezone: String,
    ): String = WHEN_FORMAT.withZone(ZoneId.of(timezone)).format(instant)

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
        val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm")
        val E164 = Regex("^\\+[1-9]\\d{6,14}$")
    }
}
