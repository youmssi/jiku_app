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
 * A person invited to an event. Tenant-scoped (extends [BaseTenantEntity]); also
 * scoped to a specific event. At least one of [email] / [phoneNumber] is always
 * present (enforced at import time).
 */
@Entity
@Table(name = "guest")
class Guest(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "first_name", nullable = false)
    var firstName: String,
    @Column(name = "last_name", nullable = false)
    var lastName: String,
    @Column(name = "email")
    var email: String? = null,
    @Column(name = "phone_number")
    var phoneNumber: String? = null,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "rsvp_status", nullable = false)
    var rsvpStatus: RsvpStatus = RsvpStatus.PENDING

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
