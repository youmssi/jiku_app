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
    /** Nul pour un titulaire de rendez-vous (JIKU-87) : il n'appartient à aucun événement. */
    @Column(name = "event_id", updatable = false)
    val eventId: UUID?,
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

    /** True once the guest's personal identifiers have been irreversibly anonymized. */
    @Column(name = "personal_data_erased", nullable = false)
    var personalDataErased: Boolean = false

    @Column(name = "erased_at")
    var erasedAt: Instant? = null

    /** Catégorie d'accès, si l'événement en définit (JIKU-93). */
    @Column(name = "ticket_type_id")
    var ticketTypeId: UUID? = null

    /** True when the organizer has opted this guest out of future invitation sends. */
    @Column(name = "excluded_from_invitations", nullable = false)
    var excludedFromInvitations: Boolean = false

    /** Guest who now holds this place, once it has been handed over (JIKU-64). */
    @Column(name = "transferred_to_guest_id")
    var transferredToGuestId: UUID? = null

    /** Guest this place was received from, for a guest created by a transfer. */
    @Column(name = "transferred_from_guest_id")
    var transferredFromGuestId: UUID? = null

    @Column(name = "transferred_at")
    var transferredAt: Instant? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
