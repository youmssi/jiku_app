package com.jiku.billing.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PaymentRepository : JpaRepository<Payment, UUID> {
    /** The current tenant's payments, newest first (billing history — JIKU-35). */
    fun findByOrderByCreatedAtDesc(): List<Payment>

    fun findByEventIdOrderByCreatedAtDesc(eventId: UUID): List<Payment>
}
