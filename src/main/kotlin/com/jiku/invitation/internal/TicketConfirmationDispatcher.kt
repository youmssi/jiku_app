package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.MessageLanguage
import com.jiku.shared.TicketConfirmedNotice
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

/** A guest's confirmation committed; raised by [RsvpService] inside its transaction. */
data class TicketConfirmed(
    val guestId: UUID,
    val eventId: UUID,
    val tenantId: String,
)

/**
 * Sends a confirmed guest their ticket by email (JIKU-129). Listens for the
 * committed confirmation only, off the request thread so the guest's page never
 * waits on the email provider, and hands delivery to the messaging module.
 * Guests reached only by WhatsApp keep their ticket on the invitation page.
 */
@Component
class TicketConfirmationDispatcher(
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val tokenService: InvitationTokenService,
    private val properties: InvitationSendProperties,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Async("invitationExecutor")
    @TransactionalEventListener
    fun onTicketConfirmed(confirmed: TicketConfirmed) {
        val guest = guests.findById(confirmed.guestId).orElse(null) ?: return
        val email = guest.email?.takeIf { it.isNotBlank() } ?: return
        val event = events.findEvent(confirmed.eventId) ?: return
        val tenant = tenants.findTenant(UUID.fromString(confirmed.tenantId))
        val language = MessageLanguage.forCountry(tenant?.country)
        val token = tokenService.issue(confirmed.guestId, confirmed.eventId, confirmed.tenantId)
        val prefix = if (language == MessageLanguage.FRENCH) "" else "/$language"
        val category = guest.ticketTypeId?.let { typeId -> events.ticketTypes(confirmed.eventId).firstOrNull { it.id == typeId }?.label }

        eventPublisher.publishEvent(
            TicketConfirmedNotice(
                guestId = confirmed.guestId,
                tenantId = confirmed.tenantId,
                eventId = confirmed.eventId,
                recipient = email,
                recipientName = "${guest.firstName} ${guest.lastName}".trim(),
                eventName = event.name,
                eventStart = event.startDateTime,
                eventEnd = event.endDateTime,
                eventTimezone = event.timezone,
                eventLocation = event.location,
                categoryName = category,
                organizerName = tenant?.displayName ?: event.name,
                primaryColor = tenant?.primaryColor ?: DEFAULT_COLOR,
                logoUrl = tenant?.logoUrl,
                ticketUrl = "${properties.appBaseUrl}$prefix/invitation/$token/ticket",
                language = language,
            ),
        )
    }

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
    }
}
