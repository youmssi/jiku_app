package com.jiku.messaging.internal

import com.jiku.shared.ReminderDelivered
import com.jiku.shared.ReminderDue
import com.jiku.shared.TenantContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * Entrée messaging d'un rappel (JIKU-89) : [ReminderDue] publié par le module
 * ticket → livraison WhatsApp (rendu, garde-fous, envoi, audit), puis
 * [ReminderDelivered] renvoyé pour clôturer la ligne d'idempotence du ticket.
 */
@Component
class ReminderNotificationListener(
    private val notificationService: NotificationService,
    private val events: ApplicationEventPublisher,
) {
    @EventListener
    @Transactional
    fun onReminderDue(event: ReminderDue) {
        val previousTenant = TenantContext.get()
        TenantContext.set(event.tenantId)
        try {
            val outcome = notificationService.deliverAppointmentReminder(event)
            events.publishEvent(
                ReminderDelivered(
                    reminderId = event.reminderId,
                    tenantId = event.tenantId,
                    delivered = outcome.delivered,
                    queued = outcome.queued,
                    attempts = outcome.attempts,
                    error = outcome.error,
                ),
            )
        } finally {
            if (previousTenant != null) TenantContext.set(previousTenant) else TenantContext.clear()
        }
    }
}
