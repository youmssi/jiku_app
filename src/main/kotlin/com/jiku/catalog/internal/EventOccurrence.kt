package com.jiku.catalog.internal

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
 * Une date d'un événement multi-dates / récurrent (ADR 103). L'événement racine
 * porte la facturation (union des invités distincts) ; chaque occurrence porte son
 * propre créneau et sa propre capacité « salle ». Tenant-scopée.
 */
@Entity
@Table(name = "event_occurrence")
class EventOccurrence(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "starts_at", nullable = false)
    var startsAt: Instant,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "ends_at")
    var endsAt: Instant? = null

    @Column(name = "capacity")
    var capacity: Int? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
