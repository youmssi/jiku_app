package com.jiku.ticket.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDate
import java.util.UUID

/**
 * Prochain rang du jour d'un service pour une journée locale (JIKU-88).
 *
 * Volontairement une ligne plutôt qu'une séquence PostgreSQL : la séquence
 * continue d'avancer si la transaction qui l'entoure se replie, laissant des
 * trous — et surtout elle ne sait pas partager un compteur par (service, jour).
 * Cette ligne est incrémentée dans la transaction de l'arrivée sous verrou
 * pessimiste, comme la numérotation des factures (JIKU-69) : deux arrivées
 * simultanées obtiennent des rangs distincts, et un repli rend le rang.
 */
@Entity
@Table(
    name = "ticket_day_counter",
    uniqueConstraints = [
        UniqueConstraint(name = "uq_ticket_day_counter_service_day", columnNames = ["service_id", "day"]),
    ],
)
class TicketDayCounter(
    @Column(name = "service_id", nullable = false, updatable = false)
    val serviceId: UUID,
    /** Journée locale du service dont ce compteur distribue les rangs. */
    @Column(name = "day", nullable = false, updatable = false)
    val day: LocalDate,
    /** Prochain rang à distribuer : la ligne naît à 1, incrémentée sous verrou. */
    @Column(name = "next_rank", nullable = false)
    var nextRank: Int,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}
