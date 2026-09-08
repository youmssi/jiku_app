package com.jiku.ticket.internal

import com.jiku.shared.ReminderOffsets
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.UnexpectedRollbackException
import java.time.Instant
import java.util.UUID

/**
 * Balayage des rappels dus pour un service (JIKU-89). Pour chaque décalage
 * configuré, on cherche les rendez-vous à venir dont l'instant de rappel
 * (créneau − décalage) est atteint et qui n'ont pas encore de ligne posée. Un
 * décalage dont l'échéance précède l'émission du billet est sauté : le client a
 * réservé trop tard pour ce palier (ex. J-1 pour un créneau réservé il y a
 * deux heures).
 */
@Service
class AppointmentReminderSweep(
    private val reminders: AppointmentReminderRepository,
    private val claims: AppointmentReminderClaims,
) {
    /**
     * Réserve et déclenche les rappels dus du service. [channel] est la valeur de
     * la colonne reminder_channel ; seul le canal WHATSAPP est aujourd'hui livrable
     * (le parcours de réservation ne capture que le téléphone).
     */
    fun sweepService(
        serviceId: UUID,
        channel: String,
        offsetsRaw: String?,
        tenantId: String,
        serviceTimezone: String,
        now: Instant = Instant.now(),
    ) {
        if (channel != CHANNEL_WHATSAPP) {
            return
        }
        // Les décalages du canal sont persistés à l'activation (défauts J-1/H-2 si
        // non précisés). Une ligne héritée créée avant ce comportement peut encore
        // porter une valeur nulle : on retombe alors sur les défauts du produit
        // plutôt que de ne jamais rappeler.
        val offsets = ReminderOffsets.parse(offsetsRaw).orEmpty().ifEmpty { DEFAULT_OFFSETS }
        for (offsetMinutes in offsets) {
            val until = now.plusSeconds(offsetMinutes * 60L)
            for (ticket in reminders.findRemindableForOffset(serviceId, offsetMinutes, now, until)) {
                val startsAt = ticket.startsAt ?: continue
                val dueAt = startsAt.minusSeconds(offsetMinutes * 60L)
                if (dueAt.isAfter(now) || dueAt.isBefore(ticket.issuedAt)) {
                    continue
                }
                try {
                    claims.claim(ticket, offsetMinutes, tenantId, serviceTimezone)
                } catch (ex: DataIntegrityViolationException) {
                    // Une autre instance a réservé (billet, décalage) entre la
                    // vérification et l'insertion : sa transaction REQUIRES_NEW est
                    // déjà annulée, la ligne existe — on passe au candidat suivant.
                    log.debug("Rappel déjà réservé (ticket {}, offset {} min)", ticket.id, offsetMinutes)
                } catch (ex: UnexpectedRollbackException) {
                    // Violation d'unicité remontée à l'engagement de la transaction
                    // de réservation ; même traitement que ci-dessus.
                    log.debug("Rappel déjà réservé à l'engagement (ticket {}, offset {} min)", ticket.id, offsetMinutes)
                }
            }
        }
    }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(AppointmentReminderSweep::class.java)

        /** Défauts du produit (J-1 / H-2), appliqués quand un canal activé n'a aucun décalage. */
        private val DEFAULT_OFFSETS = listOf(1440, 120)
    }
}

private const val CHANNEL_WHATSAPP = "WHATSAPP"
