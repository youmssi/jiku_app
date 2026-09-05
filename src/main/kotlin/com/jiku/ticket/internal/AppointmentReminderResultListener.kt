package com.jiku.ticket.internal

import com.jiku.shared.ReminderDelivered
import com.jiku.shared.TenantContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Clôture d'une ligne de rappel (JIKU-89). Reçoit l'issue de la livraison du
 * module messaging et range le statut : SENT, QUEUED (garde-fou — le prochain
 * balayage le rejoue) ou FAILED (journalisé, jamais remonté au client). La ligne
 * est rechargée sous le contexte tenant du rappel.
 */
@Component
class AppointmentReminderResultListener(
    private val reminders: AppointmentReminderRepository,
) {
    @EventListener
    @Transactional
    fun onReminderDelivered(event: ReminderDelivered) {
        val previous = TenantContext.get()
        TenantContext.set(event.tenantId)
        try {
            val row = reminders.findById(event.reminderId).orElse(null) ?: return
            row.attempts = event.attempts
            when {
                event.delivered -> {
                    row.status = ReminderStatus.SENT
                    row.sentAt = Instant.now()
                    row.error = null
                }
                event.queued -> row.status = ReminderStatus.QUEUED
                else -> {
                    row.status = ReminderStatus.FAILED
                    row.error = event.error?.take(500)
                }
            }
        } finally {
            if (previous == null) TenantContext.clear() else TenantContext.set(previous)
        }
    }
}
