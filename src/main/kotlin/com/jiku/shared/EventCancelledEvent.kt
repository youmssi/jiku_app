package com.jiku.shared

import java.util.UUID

/**
 * Published by the event module when an organizer cancels a published event.
 * Lives in `shared` (a shared kernel) so consumers do not depend on the event
 * module's internals and the Modulith boundary stays acyclic:
 *
 * - the ticketing module invalidates every ticket **synchronously, inside the
 *   cancelling transaction**, so there is no state where the event is cancelled
 *   but tickets remain valid;
 * - the invitation module fans out per-guest [EventCancellationNotice]s after the
 *   cancellation has committed.
 */
data class EventCancelledEvent(
    val eventId: UUID,
    val tenantId: String,
)
