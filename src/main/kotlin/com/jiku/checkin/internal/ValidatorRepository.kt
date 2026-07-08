package com.jiku.checkin.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ValidatorRepository : JpaRepository<Validator, UUID> {
    fun findByEventIdOrderByCreatedAtAsc(eventId: UUID): List<Validator>
}
