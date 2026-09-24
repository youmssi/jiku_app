package com.jiku.shared

import java.util.UUID

/**
 * Published by the event module when an organizer deletes a non-published event.
 * Lives in `shared` (a shared kernel) so consumers do not depend on the event
 * module's internals and the Modulith boundary stays acyclic. Consumers purge
 * their own rows **synchronously, inside the deleting transaction**, so the
 * event and its data disappear together rather than leaving orphans behind:
 *
 * - the ticketing module removes every ticket of the event;
 * - the invitation module removes every invitation and guest of the event.
 *
 * Operators lose the event from their scope through the FK cascade.
 */
data class EventDeletedEvent(
    val eventId: UUID,
    val tenantId: String,
)
