package com.jiku.ticket.internal

import com.jiku.shared.ReminderDue
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Réservation d'une ligne de rappel avant envoi (JIKU-89). Chaque réservation
 * vit dans sa propre transaction (REQUIRES_NEW) : si un rappel concurrent est
 * déjà réservé, la violation d'unicité n'emporte que cette ligne, pas le reste
 * du balayage. L'événement [ReminderDue] est publié dans la même transaction :
 * la livraison (module messaging) et la clôture de la ligne se jouent avant
 * l'engagement. Le balayage appelle toujours sous contexte tenant lié.
 */
@Component
class AppointmentReminderClaims(
    private val reminders: AppointmentReminderRepository,
    private val tickets: TicketRepository,
    private val events: ApplicationEventPublisher,
) {
    /** Réserve (billet, décalage) et déclenche l'envoi. Faux si déjà réservé. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun claim(
        ticket: Ticket,
        offsetMinutes: Int,
        tenantId: String,
        serviceTimezone: String,
    ): Boolean {
        val ticketId = ticket.id ?: return false
        val startsAt = ticket.startsAt ?: return false
        if (reminders.existsByTicketIdAndOffsetMinutes(ticketId, offsetMinutes)) {
            return false
        }
        val row =
            AppointmentReminder(
                ticketId = ticketId,
                serviceId = requireNotNull(ticket.serviceId),
                offsetMinutes = offsetMinutes,
                dueAt = startsAt.minusSeconds(offsetMinutes * 60L),
                channel = CHANNEL_WHATSAPP,
            )
        reminders.save(row)
        events.publishEvent(row.due(ticket, tenantId, serviceTimezone))
        return true
    }

    /** Rejoue un rappel retenu par une garde-fou au balayage précédent. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun requeue(
        reminderId: UUID,
        tenantId: String,
    ) {
        val row = reminders.findById(reminderId).orElse(null) ?: return
        if (row.status != ReminderStatus.QUEUED) {
            return
        }
        val ticket = tickets.findById(row.ticketId).orElse(null) ?: return
        val timezone = reminders.serviceTimezone(row.serviceId) ?: DEFAULT_TIMEZONE
        events.publishEvent(row.due(ticket, tenantId, timezone))
    }

    private fun AppointmentReminder.due(
        ticket: Ticket,
        tenantId: String,
        serviceTimezone: String,
    ): ReminderDue =
        ReminderDue(
            reminderId = requireNotNull(id),
            serviceId = serviceId,
            tenantId = tenantId,
            offsetMinutes = offsetMinutes,
            clientName = ticket.clientName,
            clientPhone = requireNotNull(ticket.clientPhone),
            startsAt = requireNotNull(ticket.startsAt),
            professionalName = ticket.professionalName,
            serviceTimezone = serviceTimezone,
        )
}

private const val CHANNEL_WHATSAPP = "WHATSAPP"
private const val DEFAULT_TIMEZONE = "UTC"
