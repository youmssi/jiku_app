package com.jiku.catalog.internal

import com.jiku.shared.AppointmentCancelled
import com.jiku.shared.TenantContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant

/**
 * Annulation d'un rendez-vous côté client (JIKU-87/89). Transactionnel : la
 * suppression des lignes service_reservation et la publication de
 * [AppointmentCancelled] (qui fait annuler le billet dans le module ticket) sont
 * engagées ensemble. Le contrôleur public lie le tenant avant d'appeler ce
 * service, pour que la session ouverte par la transaction résolve le bon tenant.
 */
@Service
class AppointmentCancellationService(
    private val reservations: ServiceReservationRepository,
    private val config: ServiceConfigService,
    private val events: ApplicationEventPublisher,
) {
    @Transactional
    fun cancel(rawBookingToken: String) {
        val rows = reservations.findByBookingTokenHash(BookingToken.hash(rawBookingToken))
        if (rows.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cette réservation est introuvable")
        }
        val first = rows.first()
        val eff = config.effective(first.serviceId)
        val cancelDeadline = Instant.now().plusSeconds(eff.cancelDeadlineHours * 3600L)
        if (first.startsAt.isBefore(cancelDeadline)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Ce rendez-vous ne peut plus être annulé")
        }
        events.publishEvent(
            AppointmentCancelled(
                serviceId = first.serviceId,
                startsAt = first.startsAt,
                tenantId = requireNotNull(TenantContext.get()),
            ),
        )
        reservations.deleteAll(rows)
    }
}
