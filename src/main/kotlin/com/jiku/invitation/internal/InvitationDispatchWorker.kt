package com.jiku.invitation.internal

import com.jiku.event.EventModuleApi
import com.jiku.notification.InvitationEmail
import com.jiku.notification.NotificationModuleApi
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Delivers a single invitation, resolving the guest, event and tenant branding,
 * building the signed invitation link, and sending the branded email with bounded
 * retry. Runs in its own transaction (called from the async dispatcher).
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
        val recipient = guest?.email
        if (guest == null || recipient == null) {
            invitation.status = InvitationStatus.FAILED
            invitation.lastError = "Guest has no email address"
            invitations.save(invitation)
            return
        }

        val event = events.findEvent(invitation.eventId)
        val tenant = TenantContext.get()?.let { tenants.findTenant(UUID.fromString(it)) }
        val token = tokenService.issue(invitation.guestId, invitation.eventId)
        val email =
            InvitationEmail(
                recipientEmail = recipient,
                recipientName = "${guest.firstName} ${guest.lastName}",
                eventName = event?.name ?: "your event",
                eventWhen = event?.startDateTime?.let { formatWhen(it, event.timezone) },
                eventLocation = event?.location,
                organizerName = tenant?.displayName ?: "Your organizer",
                primaryColor = tenant?.primaryColor ?: DEFAULT_COLOR,
                logoUrl = tenant?.logoUrl,
                invitationUrl = "${properties.appBaseUrl}/invitation/$token",
            )

        var lastError: String? = null
        repeat(properties.maxAttempts) {
            invitation.attempts += 1
            try {
                notifications.sendInvitationEmail(email)
                invitation.status = InvitationStatus.SENT
                invitation.sentAt = Instant.now()
                invitation.lastError = null
                invitations.save(invitation)
                return
            } catch (ex: Exception) {
                lastError = ex.message ?: ex.javaClass.simpleName
            }
        }
        invitation.status = InvitationStatus.FAILED
        invitation.lastError = lastError
        invitations.save(invitation)
    }

    private fun formatWhen(
        instant: Instant,
        timezone: String,
    ): String = WHEN_FORMAT.withZone(ZoneId.of(timezone)).format(instant)

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
        val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM yyyy 'at' HH:mm")
    }
}
