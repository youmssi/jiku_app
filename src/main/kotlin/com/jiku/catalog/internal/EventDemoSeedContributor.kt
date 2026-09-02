package com.jiku.catalog.internal

import com.jiku.catalog.InvitationChannel
import com.jiku.shared.DemoSeedContext
import com.jiku.shared.DemoSeedContributor
import com.jiku.shared.DemoSeedPlan
import org.springframework.stereotype.Component

/**
 * Seeds the demo events (a draft, a published upcoming event, a published past
 * event). The confirmed-attendance counter is set from the plan so it matches the
 * guests the invitation/ticketing contributors will confirm, without the modules
 * having to coordinate at runtime.
 */
@Component
class EventDemoSeedContributor(
    private val eventService: EventService,
    private val events: EventRepository,
) : DemoSeedContributor {
    override val order = 10

    override fun wipe() {
        events.deleteAll()
    }

    override fun seed(context: DemoSeedContext) {
        DemoSeedPlan.events.forEach { planned ->
            val eventId =
                eventService
                    .create(
                        CreateEventRequest(
                            name = planned.name,
                            description = planned.description,
                            startDateTime = planned.start,
                            endDateTime = planned.end,
                            timezone = DemoSeedPlan.TIMEZONE,
                            location = planned.location,
                            maxCapacity = planned.capacity,
                            invitationChannels = setOf(InvitationChannel.EMAIL),
                        ),
                    ).id
            if (planned.publish) {
                eventService.publish(eventId)
            }
            val confirmed = DemoSeedPlan.confirmedCount(planned.key)
            if (confirmed > 0) {
                val event = events.findById(eventId).orElseThrow()
                event.confirmedCount = confirmed
                events.save(event)
            }
            context.eventIds[planned.key] = eventId
        }
    }
}
