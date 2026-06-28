package com.jiku.event

import java.util.UUID

/**
 * The event module's public API. Other modules (invitation, ticketing, checkin)
 * read events through this interface only. Reads are scoped to the current tenant
 * by the persistence-layer tenant filter, so an event is only visible within its
 * own tenant's context.
 *
 * Kept deliberately minimal and stable — many modules depend on it.
 */
interface EventModuleApi {
    fun findEvent(eventId: UUID): EventInfo?
}
