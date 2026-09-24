package com.jiku.catalog.internal

import com.jiku.shared.AppointmentBooked
import com.jiku.shared.TenantContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Demandes de rendez-vous en attente de confirmation (JIKU-87/88), mode « sur
 * demande ». Une réservation cliente naît PENDING et bloque ses créneaux jusqu'à
 * expiration ; elle ne matérialise ni invité ni billet. Ce service permet à
 * l'organisateur (ou au personnel de comptoir) de la lister, de la confirmer — ce
 * qui émet alors l'invité et le billet via [AppointmentBooked] — ou de la refuser —
 * ce qui libère la case. Tenant-scopé : le service est résolu dans le tenant
 * courant, qu'il vienne du compte organisateur ou du lien signé du personnel.
 */
@Service
class AppointmentRequestService(
    private val reservations: ServiceReservationRepository,
    private val resources: ResourceRepository,
    private val services: ServiceAdminService,
    private val events: ApplicationEventPublisher,
) {
    /** Demandes en attente d'un service pour [date] (aujourd'hui dans le fuseau du service si absente). */
    @Transactional(readOnly = true)
    fun pending(
        serviceId: UUID,
        date: LocalDate?,
    ): List<PendingAppointmentRequest> {
        val service = services.get(serviceId)
        val zone = ZoneId.of(service.timezone)
        val day = date ?: LocalDate.now(zone)
        val start = day.atStartOfDay(zone).toInstant()
        val end = day.plusDays(1).atStartOfDay(zone).toInstant()
        // Une demande occupe plusieurs lignes (une par ressource) qui partagent le
        // même jeton client : on n'expose qu'une entrée par demande.
        val requests = LinkedHashMap<String, PendingAppointmentRequest>()
        for (row in reservations.findPendingBetween(serviceId, start, end)) {
            val hash = requireNotNull(row.bookingTokenHash)
            requests.putIfAbsent(
                hash,
                PendingAppointmentRequest(
                    id = requireNotNull(row.id),
                    startsAt = row.startsAt,
                    endsAt = row.endsAt,
                    clientName = row.clientName,
                    clientPhone = row.clientPhone,
                    requestedAt = row.createdAt,
                    heldUntil = row.heldUntil,
                ),
            )
        }
        return requests.values.toList()
    }

    /**
     * Confirme une demande en attente : la réservation passe CONFIRMED (condition
     * atomique contre une décision concurrente ou l'expiration), puis l'invité et
     * le billet de rendez-vous sont matérialisés dans la même transaction via
     * [AppointmentBooked]. Sans effet propre si la demande a déjà été traitée.
     */
    @Transactional
    fun accept(
        serviceId: UUID,
        requestId: UUID,
    ) {
        val row = loadRequest(serviceId, requestId)
        if (row.status != ServiceReservationStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This request has already been handled")
        }
        val hash = requireNotNull(row.bookingTokenHash)
        val now = Instant.now()
        val heldUntil = row.heldUntil
        if (heldUntil != null && heldUntil <= now) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This request has expired")
        }
        if (reservations.confirmByTokenHash(hash, now) == 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This request has already been handled")
        }
        val rows = reservations.findByBookingTokenHash(hash)
        val confirmed = rows.first()
        val professionalName = resources.professionalAmong(rows.map { it.resourceId })
        val tenantId = TenantContext.get() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant")
        events.publishEvent(
            AppointmentBooked(
                serviceId = confirmed.serviceId,
                tenantId = tenantId,
                startsAt = confirmed.startsAt,
                endsAt = confirmed.endsAt,
                clientName = requireNotNull(confirmed.clientName) { "Request without a client name" },
                clientPhone = requireNotNull(confirmed.clientPhone) { "Request without a client phone" },
                professionalName = professionalName,
                charge = services.clientCharge(confirmed.serviceId),
            ),
        )
    }

    /** Refuse une demande en attente : ses lignes sont supprimées, la case redevient réservable. */
    @Transactional
    fun reject(
        serviceId: UUID,
        requestId: UUID,
    ) {
        val row = loadRequest(serviceId, requestId)
        if (row.status != ServiceReservationStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This request has already been handled")
        }
        val hash = requireNotNull(row.bookingTokenHash)
        reservations.deleteByTokenHash(hash)
    }

    private fun loadRequest(
        serviceId: UUID,
        requestId: UUID,
    ): ServiceReservation {
        val row =
            reservations.findById(requestId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This request was not found")
        if (row.serviceId != serviceId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This request was not found")
        }
        return row
    }
}
