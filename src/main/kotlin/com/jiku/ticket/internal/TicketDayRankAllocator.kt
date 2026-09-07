package com.jiku.ticket.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

/**
 * Crée la ligne de compteur d'un service pour une journée si elle n'existe pas
 * (JIKU-88).
 *
 * Tourne dans sa propre transaction pour la même raison que la numérotation des
 * factures : dans PostgreSQL une instruction en échec aborte toute la
 * transaction, donc laisser une course perdue échouer dans la transaction de
 * l'arrivée la rendrait inutilisable — or cette transaction a encore un billet à
 * écrire. `REQUIRES_NEW` confine l'abandon à une transaction qu'on accepte de
 * jeter.
 *
 * La violation d'unicité n'est volontairement **pas** attrapée ici : la rattraper
 * laisserait la transaction marquée rollback-only et l'engagement échouerait de
 * toute façon. L'appelant la rattrape à l'extérieur de cette frontière, où la
 * transaction en échec a déjà été proprement rejouée. Le perdant de la course
 * bloque sur l'index unique jusqu'à l'engagement du gagnant : à la sortie de
 * cette méthode, la ligne existe.
 */
@Component
class TicketDayRankAllocator(
    private val counters: TicketDayCounterRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureCounterExists(
        serviceId: UUID,
        day: LocalDate,
    ) {
        if (counters.findExisting(serviceId, day) != null) {
            return
        }
        counters.saveAndFlush(TicketDayCounter(serviceId = serviceId, day = day, nextRank = 1))
    }
}
