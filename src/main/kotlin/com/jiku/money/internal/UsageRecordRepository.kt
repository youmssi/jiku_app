package com.jiku.money.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UsageRecordRepository : JpaRepository<UsageRecord, UUID> {
    /** The current tenant's usage record for an event, if one exists yet. */
    fun findByEventId(eventId: UUID): UsageRecord?
}
