package com.jiku.catalog.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

enum class ServiceReservationStatus {
    /** Demande en attente de confirmation : la case est bloquée jusqu'à [heldUntil]. */
    PENDING,

    /** Réservation confirmée : la case est prise. */
    CONFIRMED,
}

/**
 * L'occupation d'une place d'une ressource sur un créneau (JIKU-85, JIKU-174).
 * L'unicité (resource_id, starts_at, seat) est la garde de concurrence : deux
 * clients ne prennent jamais la même place, et le gagnant est celui dont
 * l'INSERT aboutit. Hors séance collective, la seule place est 0.
 */
@Entity
@Table(
    name = "service_reservation",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_reservation_resource_start_seat", columnNames = ["resource_id", "starts_at", "seat"]),
    ],
)
class ServiceReservation(
    @Column(name = "service_id", nullable = false, updatable = false)
    val serviceId: UUID,
    @Column(name = "resource_id", nullable = false, updatable = false)
    val resourceId: UUID,
    @Column(name = "starts_at", nullable = false, updatable = false)
    val startsAt: Instant,
    @Column(name = "ends_at", nullable = false, updatable = false)
    val endsAt: Instant,
    /** La place occupée dans la séance, de 0 à la capacité moins un. */
    @Column(name = "seat", nullable = false, updatable = false)
    val seat: Int = 0,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    var status: ServiceReservationStatus = ServiceReservationStatus.PENDING

    /** Fin du blocage d'une demande en attente ; nulle pour une confirmation. */
    @Column(name = "held_until")
    var heldUntil: Instant? = null

    /** Nom du client qui a réservé (JIKU-87) ; nul pour les réservations d'essai/moteur. */
    @Column(name = "client_name")
    var clientName: String? = null

    @Column(name = "client_phone")
    var clientPhone: String? = null

    /** Hash du jeton de réservation remis au client ; partagé par toutes les lignes d'une réservation. */
    @Column(name = "booking_token_hash")
    var bookingTokenHash: String? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
