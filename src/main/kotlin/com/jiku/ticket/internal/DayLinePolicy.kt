package com.jiku.ticket.internal

import java.time.Instant
import java.util.UUID

/**
 * Vue minimale d'un ticket pour la politique « suivant » — pure, sans entité,
 * pour que la règle soit testable sans base.
 */
data class LineCandidate(
    val ticketId: UUID,
    val kind: TicketKind,
    val status: TicketStatus,
    val startsAt: Instant?,
    val arrivedAt: Instant?,
)

/**
 * La règle « suivant » de la ligne du jour (JIKU-88, §4.1). Un seul paramètre,
 * la [toleranceMinutes] : au-delà, un rendez-vous non arrivé perd sa priorité et
 * rejoint la file à son arrivée.
 */
object DayLinePolicy {
    /**
     * 1. le rendez-vous dont le créneau est en cours, si le client est arrivé
     *    (priorité maintenue [toleranceMinutes] après l'heure) ;
     * 2. sinon, la personne qui attend depuis le plus longtemps.
     */
    fun nextToCall(
        candidates: List<LineCandidate>,
        now: Instant,
        toleranceMinutes: Long,
    ): LineCandidate? {
        val waiting = candidates.filter { it.status == TicketStatus.WAITING && it.arrivedAt != null }
        if (waiting.isEmpty()) {
            return null
        }
        val inSlot =
            waiting.filter {
                it.kind == TicketKind.APPOINTMENT && isWithinTolerance(it.startsAt, now, toleranceMinutes)
            }
        if (inSlot.isNotEmpty()) {
            return inSlot.minWith(compareBy { it.startsAt })
        }
        return waiting.minWith(compareBy { it.arrivedAt })
    }

    private fun isWithinTolerance(
        start: Instant?,
        now: Instant,
        toleranceMinutes: Long,
    ): Boolean {
        val slotStart = start ?: return false
        return !now.isBefore(slotStart) && now.isBefore(slotStart.plusSeconds(toleranceMinutes * 60))
    }
}
