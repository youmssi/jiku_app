package com.jiku.ticket.internal

import com.jiku.shared.TenantContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Balayage des rappels de rendez-vous (JIKU-89), plateforme entière : services
 * avec rappels activés puis rappels retenus par une garde-fou. Chaque ligne est
 * traitée sous le tenant qui la porte, comme les autres balayages (NotificationQueueSweepJob).
 * L'idempotence repose sur la contrainte d'unicité (billet, décalage) : deux
 * instances du job ne doublonnent jamais un rappel.
 */
@Component
class AppointmentReminderJob(
    private val reminders: AppointmentReminderRepository,
    private val sweep: AppointmentReminderSweep,
    private val claims: AppointmentReminderClaims,
) {
    @Scheduled(cron = "\${appointment.reminder.sweep-cron:0 */5 * * * *}")
    fun sweep() {
        val now = Instant.now()
        for (ref in reminders.findQueuedReminders()) {
            withTenant(ref.tenantId) { claims.requeue(ref.reminderId, ref.tenantId) }
        }
        for (ref in reminders.findReminderEnabledServices()) {
            withTenant(ref.tenantId) {
                sweep.sweepService(ref.serviceId, ref.channel, ref.offsets, ref.tenantId, ref.timezone, now)
            }
        }
    }

    private fun <T> withTenant(
        tenantId: String,
        block: () -> T,
    ): T {
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        return try {
            block()
        } finally {
            if (previous == null) {
                TenantContext.clear()
            } else {
                TenantContext.set(previous)
            }
        }
    }
}
