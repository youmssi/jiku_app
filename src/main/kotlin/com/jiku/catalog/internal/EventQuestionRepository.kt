package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EventQuestionRepository : JpaRepository<EventQuestion, UUID> {
    fun findByEventIdOrderByPositionAsc(eventId: UUID): List<EventQuestion>
}
