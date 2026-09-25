package com.jiku.invitation.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.shared.ApiProperties
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.MessageLanguage
import com.jiku.shared.TicketConfirmedNotice
import com.jiku.tenant.TenantModuleApi
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Builds the ticket a confirmed guest receives (JIKU-129), whether they
 * confirmed on their page or the event sends tickets directly (ADR 105): the
 * signed ticket link in the organization's language, the event's schedule for
 * the calendar invite, the organizer's brand, and the QR code as an image for
 * WhatsApp (JIKU-143).
 */
@Component
class TicketNotices(
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val tokenService: InvitationTokenService,
    private val properties: InvitationSendProperties,
    private val api: ApiProperties,
) {
    fun build(
        guest: Guest,
        event: EventInfo,
        tenantId: String,
        recipient: String,
        channel: String = GuestInvitedEvent.CHANNEL_EMAIL,
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
            organizerName = event.brand.name ?: tenant?.displayName ?: event.name,
            primaryColor = event.brand.primaryColor ?: tenant?.primaryColor ?: DEFAULT_COLOR,
            logoUrl = event.brand.logoUrl ?: tenant?.logoUrl,
            ticketUrl = "${properties.appBaseUrl}$prefix/invitation/$token/ticket",
            language = language,
            channel = channel,
            qrImageUrl = "${properties.apiPublicUrl}${api.basePath}/rsvp/$token/qr.png",
        )
    }

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
    }
}
