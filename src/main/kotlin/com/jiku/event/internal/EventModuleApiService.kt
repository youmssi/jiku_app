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
}
