package com.jiku.invitation.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.shared.MessageLanguage
import com.jiku.shared.TicketConfirmedNotice
import com.jiku.tenant.TenantModuleApi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Builds the ticket a confirmed guest receives (JIKU-129), whether they
 * confirmed on their page or the event sends tickets directly (ADR 105): the
 * signed ticket link in the organization's language, the event's schedule for
 * the calendar invite, and the organizer's brand.
 */
@Component
class TicketNotices(
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val tokenService: InvitationTokenService,
    private val properties: InvitationSendProperties,
) {
    fun build(
        guest: Guest,
        event: EventInfo,
        tenantId: String,
        recipient: String,
    ): TicketConfirmedNotice {
        val guestId = requireNotNull(guest.id)
        val tenant = tenants.findTenant(UUID.fromString(tenantId))
        val language = MessageLanguage.forCountry(tenant?.country)
        val token = tokenService.issue(guestId, event.id, tenantId)
        val prefix = if (language == MessageLanguage.FRENCH) "" else "/$language"
        val category = guest.ticketTypeId?.let { typeId -> events.ticketTypes(event.id).firstOrNull { it.id == typeId }?.label }
        return TicketConfirmedNotice(
            guestId = guestId,
            tenantId = tenantId,
            eventId = event.id,
            recipient = recipient,
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
        )
    }

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
    }
}
