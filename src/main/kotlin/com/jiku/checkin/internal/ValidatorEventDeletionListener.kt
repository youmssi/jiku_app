package com.jiku.checkin.internal

import com.jiku.shared.EventDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Removes every validator link of a deleted event. Runs synchronously inside the
 * deleting transaction (MANDATORY makes that explicit), so the event and its
 * door links disappear together rather than leaving orphaned validators.
 */
@Component
class ValidatorEventDeletionListener(
    private val validators: ValidatorRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onEventDeleted(event: EventDeletedEvent) {
        validators.deleteAll(validators.findByEventIdOrderByCreatedAtAsc(event.eventId))
    }
}
