package com.jiku.catalog.internal

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import java.time.Instant
import kotlin.math.ceil

/**
 * Règle de quorum d'un événement (JIKU-94).
 *
 * Une assemblée générale est nulle sans quorum, et le quorum se compte
 * aujourd'hui à la main, dans la contestation. C'est un besoin juridique.
 *
 * [reachedAt] est écrit **une seule fois** et n'est jamais recalculé : c'est la
 * valeur probante. Si des participants repartent et que le quorum retombe sous le
 * seuil, la date d'atteinte demeure — les deux informations sont vraies, et
 * l'une ne remplace pas l'autre.
 */
@Embeddable
class EventQuorum(
    @Enumerated(EnumType.STRING)
    @Column(name = "quorum_mode")
    var mode: QuorumMode? = null,
    @Column(name = "quorum_numerator")
    var numerator: Int? = null,
    @Column(name = "quorum_denominator")
    var denominator: Int? = null,
    @Column(name = "quorum_absolute")
    var absolute: Int? = null,
    @Column(name = "quorum_reached_at")
    var reachedAt: Instant? = null,
) {
    fun isConfigured(): Boolean = mode != null && mode != QuorumMode.NONE

    /**
     * Nombre de présents exigé pour [totalGuests] inscrits, ou null si aucun
     * quorum n'est configuré.
     *
     * L'arrondi est **supérieur** : une majorité de 1/2 sur 181 membres exige 91
     * présents, pas 90. Arrondir à l'inférieur validerait une assemblée qui
     * n'atteint pas sa propre règle statutaire.
     */
    fun requiredFor(totalGuests: Long): Long? =
        when (mode) {
            QuorumMode.ABSOLUTE -> absolute?.toLong()
            QuorumMode.FRACTION -> {
                val n = numerator
                val d = denominator
                if (n == null || d == null || d == 0) null else ceil(totalGuests.toDouble() * n / d).toLong()
            }
            else -> null
        }
}

enum class QuorumMode {
    /** Aucun quorum : l'événement se comporte exactement comme avant. */
    NONE,

    /** Fraction des inscrits — 1/2, 2/3, 3/4. */
    FRACTION,

    /** Nombre absolu de présents. */
    ABSOLUTE,
}
