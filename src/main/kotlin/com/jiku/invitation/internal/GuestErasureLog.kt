package com.jiku.invitation.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Compliance audit record of a guest erasure (JIKU-36): who (the guest id) and when,
 * and why (a self-service request or the retention policy). Retained after the
 * personal data itself is anonymized, so the platform can evidence that an erasure
 * happened. Tenant-scoped.
 */
@Entity
@Table(name = "guest_erasure_log")
class GuestErasureLog(
    @Column(name = "guest_id", nullable = false, updatable = false)
    val guestId: UUID,
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, updatable = false)
    val reason: ErasureReason,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

/** Why a guest's data was erased. */
enum class ErasureReason {
    /** The guest asked for their data to be deleted. */
    GUEST_REQUEST,

    /** The retention policy anonymized data for a past event (JIKU-37). */
    RETENTION_POLICY,
}
