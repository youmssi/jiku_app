package com.jiku.invitation.internal

import com.jiku.shared.EventDeletedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Removes every invitation and guest of a deleted event. Runs synchronously
 * inside the deleting transaction (MANDATORY makes that explicit), so the event
 * and its guest list disappear together rather than leaving orphaned rows.
 */
@Component
class InvitationEventDeletionListener(
    private val guests: GuestRepository,
    private val invitations: InvitationRepository,
) {
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    fun onEventDeleted(event: EventDeletedEvent) {
        invitations.deleteAll(invitations.findByEventId(event.eventId))
        guests.deleteAll(guests.findByEventId(event.eventId))
    }
}
