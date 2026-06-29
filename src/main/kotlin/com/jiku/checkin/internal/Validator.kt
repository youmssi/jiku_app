package com.jiku.checkin.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A labeled, revocable check-in access link, scoped to a single event. The link
 * itself is a signed token; this tenant-scoped row backs immediate revocation and
 * attributes each check-in performed through it to [label]. There is no associated
 * user account — validators are deliberately lightweight (JIKU-23).
 */
@Entity
@Table(name = "validator")
class Validator(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "label", nullable = false)
    var label: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "revoked", nullable = false)
    var revoked: Boolean = false

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()

    @Column(name = "revoked_at")
    var revokedAt: Instant? = null
}
