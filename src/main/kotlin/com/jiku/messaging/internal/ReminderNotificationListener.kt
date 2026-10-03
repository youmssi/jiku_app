package com.jiku.messaging.internal

import com.jiku.shared.ReminderDelivered
import com.jiku.shared.ReminderDue
import com.jiku.shared.TenantContext
import com.jiku.shared.async.Executors
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

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
    @Async(Executors.BULK)
    @TransactionalEventListener(fallbackExecution = true)
    fun onReminderDue(event: ReminderDue) {
        TenantContext.withTenant(event.tenantId) {
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
