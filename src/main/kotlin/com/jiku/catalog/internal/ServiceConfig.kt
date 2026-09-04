package com.jiku.catalog.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

/** Mode de confirmation d'un service : immédiat, ou demande à confirmer. */
enum class ConfirmationMode {
    INSTANTANEOUS,
    ON_REQUEST,
}

/** Moment du paiement d'un rendez-vous, quand le service en demande un. */
enum class PaymentMode {
    FREE,
    BEFORE,
    AFTER,
}

/**
 * Options d'un service (JIKU-86), toutes nulles par défaut : une valeur nulle
 * signifie « appliquer le défaut de configuration » (catalog.slot.* et
 * catalog.service.*). Un service créé sans configuration est donc utilisable tel
 * quel ; chaque option, une fois renseignée, est lue par le moteur. Aucune branche
 * de code métier ne dépend d'une option : ce sont des données.
 */
@Entity
@Table(name = "service_config")
class ServiceConfig(
    @Id
    @Column(name = "service_id")
    val serviceId: UUID,
) : BaseTenantEntity() {
    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_mode", length = 32)
    var confirmationMode: ConfirmationMode? = null

    @Column(name = "step_minutes")
    var stepMinutes: Int? = null

    @Column(name = "duration_minutes")
    var durationMinutes: Int? = null

    @Column(name = "buffer_minutes")
    var bufferMinutes: Int? = null

    @Column(name = "min_horizon_minutes")
    var minHorizonMinutes: Int? = null

    @Column(name = "max_horizon_days")
    var maxHorizonDays: Int? = null

    /** Durée de blocage d'une demande en attente avant libération. */
    @Column(name = "hold_minutes")
    var holdMinutes: Long? = null

    /** Délai d'annulation client, en heures avant le créneau. */
    @Column(name = "cancel_deadline_hours")
    var cancelDeadlineHours: Int? = null

    /** Tolérance de rendez-vous : minutes de priorité conservées après l'heure. */
    @Column(name = "no_show_tolerance_minutes")
    var noShowToleranceMinutes: Int? = null

    @Column(name = "walk_ins_allowed")
    var walkInsAllowed: Boolean? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_mode", length = 32)
    var paymentMode: PaymentMode? = null
}
