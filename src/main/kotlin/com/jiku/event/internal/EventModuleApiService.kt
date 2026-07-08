package com.jiku.event.internal

import com.jiku.event.EventInfo
import com.jiku.event.EventModuleApi
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class EventModuleApiService(
    private val events: EventRepository,
) : EventModuleApi {
    @Transactional(readOnly = true)
    override fun findEvent(eventId: UUID): EventInfo? = events.findById(eventId).map { it.toEventInfo() }.orElse(null)

    @Transactional
    override fun reserveAttendanceSlot(eventId: UUID): Boolean {
        val event = events.findById(eventId).orElse(null) ?: return false
        val limit =
            event.maxCapacity?.let { capacity ->
                capacity + if (event.settings.overbookingAllowed) (event.settings.maxOverbookingCount ?: 0) else 0
            } ?: Int.MAX_VALUE
        return events.reserveSlot(eventId, limit) == 1
    }

    @Transactional
    override fun releaseAttendanceSlot(eventId: UUID) {
        events.releaseSlot(eventId)
    }
}
