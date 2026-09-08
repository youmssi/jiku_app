package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EventOccurrenceRepository : JpaRepository<EventOccurrence, UUID> {
    fun findByEventIdOrderByStartsAtAsc(eventId: UUID): List<EventOccurrence>
}
