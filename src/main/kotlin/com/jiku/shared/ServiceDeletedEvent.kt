package com.jiku.shared

import java.util.UUID

/**
 * Published by the event module when an organizer deletes a service. Lives in
 * `shared` (a shared kernel) so consumers do not depend on the catalog module's
 * internals and the Modulith boundary stays acyclic. The ticketing module
 * removes every appointment ticket of the service **synchronously, inside the
 * deleting transaction**, so the service and its day-line data disappear
 * together rather than leaving orphaned tickets behind.
 */
data class ServiceDeletedEvent(
    val serviceId: UUID,
    val tenantId: String,
)
