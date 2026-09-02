package com.jiku.messaging.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** Exactly one row, seeded by migration; see [WhatsAppContentOverride]. */
interface WhatsAppContentOverrideRepository : JpaRepository<WhatsAppContentOverride, UUID> {
    fun findFirstByOrderByUpdatedAtDesc(): WhatsAppContentOverride?
}
