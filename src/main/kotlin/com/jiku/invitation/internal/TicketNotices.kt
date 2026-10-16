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
    private val inviteCodes: GuestInviteCodes,
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
        val code = inviteCodes.linkKey(guest, tenantId)
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
            ticketUrl = links(code, language).ticketUrl,
            language = language,
            channel = channel,
            qrImageUrl = links(code, language).qrImageUrl,
        )
    }

    /** The ticket page and the QR code image of the guest whose short invitation code is [code], in [language]. */
    fun links(
        code: String,
        language: String,
    ): TicketLinks {
        val prefix = if (language == MessageLanguage.FRENCH) "" else "/$language"
        return TicketLinks(
            ticketUrl = "${properties.appBaseUrl}$prefix/v/$code/ticket",
            qrImageUrl = "${properties.apiPublicUrl}${api.basePath}/rsvp/$code/qr.png",
        )
    }

    private companion object {
        const val DEFAULT_COLOR = "#1E293B"
    }
}

data class TicketLinks(
    val ticketUrl: String,
    val qrImageUrl: String,
)
