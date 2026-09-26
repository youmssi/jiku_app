package com.jiku.catalog.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Embedded
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID

/**
 * Une catégorie d'accès d'un événement (JIKU-93) : salle, carré VIP, accès scène.
 *
 * Tenant-scopé. [maxCapacity] est un plafond **propre à la catégorie** qui
 * s'ajoute à celui de l'événement — les deux doivent être respectés ensemble, et
 * c'est ce que garantit la transition atomique de
 * [TicketTypeRepository.reserveSlot].
 *
 * [colorHex] n'est pas décoratif : le portier lit le résultat de scan à deux
 * mètres, dans une lumière difficile, et la couleur porte l'information avant le
 * texte.
 */
@Entity
@Table(
    name = "ticket_type",
    uniqueConstraints = [UniqueConstraint(name = "uq_ticket_type_label", columnNames = ["event_id", "label"])],
)
class TicketType(
    @Column(name = "event_id", nullable = false, updatable = false)
    val eventId: UUID,
    @Column(name = "label", nullable = false)
    var label: String,
    @Column(name = "color_hex", nullable = false)
    var colorHex: String,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    /** Null = aucun plafond propre ; seule la capacité de l'événement s'applique. */
    @Column(name = "max_capacity")
    var maxCapacity: Int? = null

    @Column(name = "confirmed_count", nullable = false)
    var confirmedCount: Int = 0

    @Column(name = "position", nullable = false)
    var position: Int = 0

    /** Null for a free category (invitation, free registration); set for a ticket sold. */
    @Embedded
    var price: Price? = null
}
