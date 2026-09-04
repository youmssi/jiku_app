package com.jiku.invitation.internal

import com.jiku.shared.AppointmentBooked
import com.jiku.shared.TenantContext
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * À chaque réservation de rendez-vous confirmée côté catalog (JIKU-87), matérialise
 * l'invité et son billet dans le tenant du service. L'événement est traité
 * synchroniquement, dans la transaction de la réservation.
 */
@Component
class AppointmentBookedListener(
    private val clients: AppointmentClientService,
) {
    @EventListener
    fun on(booking: AppointmentBooked) {
        val previous = TenantContext.get()
        TenantContext.set(booking.tenantId)
        try {
            clients.record(booking)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }
}
