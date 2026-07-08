package com.jiku.invitation.internal

import com.jiku.event.InvitationChannel
import com.jiku.shared.DemoSeedContext
import com.jiku.shared.DemoSeedContributor
import com.jiku.shared.DemoSeedPlan
import org.springframework.stereotype.Component

/**
 * Seeds the demo guests and, for each, a SENT email invitation (they were all
 * invited). Publishes each guest's id into the shared context keyed by email so
 * the ticketing contributor can issue tickets for the confirmed ones.
 */
@Component
class InvitationDemoSeedContributor(
    private val guests: GuestRepository,
    private val invitations: InvitationRepository,
) : DemoSeedContributor {
    override val order = 20

    override fun wipe() {
        invitations.deleteAll()
        guests.deleteAll()
    }

    override fun seed(context: DemoSeedContext) {
        DemoSeedPlan.guests.forEach { planned ->
            val eventId = context.eventIds.getValue(planned.eventKey)
            val guest =
                guests.save(
                    Guest(
                        eventId = eventId,
                        firstName = planned.firstName,
                        lastName = planned.lastName,
                        email = planned.email,
                    ),
                )
            guest.rsvpStatus =
                when (planned.rsvpStatus) {
                    DemoSeedPlan.STATUS_CONFIRMED -> RsvpStatus.CONFIRMED
                    DemoSeedPlan.STATUS_DECLINED -> RsvpStatus.DECLINED
                    else -> RsvpStatus.PENDING
                }
            guests.save(guest)

            val invitation = Invitation(eventId, requireNotNull(guest.id), InvitationChannel.EMAIL)
            invitation.status = InvitationStatus.SENT
            invitation.attempts = 1
            invitation.sentAt = guest.createdAt
            invitations.save(invitation)

            context.guestIds[planned.email] = requireNotNull(guest.id)
        }
    }
}
