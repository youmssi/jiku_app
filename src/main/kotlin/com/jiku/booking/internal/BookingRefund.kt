package com.jiku.booking.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * Un remboursement réellement exécuté contre une déclaration d'acompte (JIKU-75).
 * La somme des lignes d'une même déclaration ne dépasse jamais son montant
 * (contrôle en service). L'avoir CREDIT_NOTE émis (module money) est référencé
 * pour la comptabilité du client.
 */
@Entity
@Table(name = "booking_refund")
class BookingRefund(
    @Column(name = "booking_id", nullable = false)
    val bookingId: UUID,
    @Column(name = "declaration_id", nullable = false)
    val declarationId: UUID,
    @Column(name = "amount_minor", nullable = false)
    val amountMinor: Long,
    @Column(name = "currency", nullable = false)
    val currency: String,
    @Column(name = "reason", nullable = false, length = 500)
    val reason: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "credit_note_id")
    var creditNoteId: UUID? = null

    @Column(name = "credit_note_number", length = 64)
    var creditNoteNumber: String? = null

    @Column(name = "executed_at", nullable = false)
    val executedAt: Instant = Instant.now()

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}
