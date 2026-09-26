package com.jiku.messaging.internal

import com.jiku.shared.ReminderDelivered
import com.jiku.shared.ReminderDue
import com.jiku.shared.TenantTransaction
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Entrée messaging d'un rappel (JIKU-89) : [ReminderDue] publié par le module
 * ticket → livraison WhatsApp (rendu, garde-fous, envoi, audit), puis
 * [ReminderDelivered] renvoyé pour clôturer la ligne d'idempotence du ticket.
 */
@Component
class ReminderNotificationListener(
    private val tenantTransaction: TenantTransaction,
    private val notificationService: NotificationService,
    private val events: ApplicationEventPublisher,
) {
    @EventListener
    fun onReminderDue(event: ReminderDue) {
        tenantTransaction.run(event.tenantId) {
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
        }
    }
}
