package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.MessageLanguage
import com.jiku.tenant.TenantModuleApi
import org.springframework.stereotype.Component
import java.util.UUID

/** The language an event's guests read (JIKU-293): the event's choice, else the organization's. */
@Component
class GuestLanguages(
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
) {
    fun of(
        eventId: UUID?,
        tenantId: String,
    ): String {
        val country = runCatching { UUID.fromString(tenantId) }.getOrNull()?.let { tenants.findTenant(it)?.country }
        return MessageLanguage.forGuests(eventId?.let { events.findEvent(it) }?.guestLanguage, country)
    }
}
