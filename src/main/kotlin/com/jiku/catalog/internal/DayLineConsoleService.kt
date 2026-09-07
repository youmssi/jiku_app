package com.jiku.catalog.internal

import com.jiku.shared.TenantContext
import com.jiku.shared.WalkInArrived
import com.jiku.ticket.LineActionResult
import com.jiku.ticket.LineTicket
import com.jiku.ticket.TicketingModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * Console de ligne du jour d'un service (JIKU-88), l'écran central du comptoir :
 * lecture de la journée mêlant rendez-vous et sans-rendez-vous, action
 * « suivant », transitions (arrivée, appel, prise en charge, fin, absent) et
 * inscription d'un sans-rendez-vous. Tenant-scopé — le service est résolu dans le
 * tenant courant, qu'il vienne du compte de l'organisateur ou du lien signé du
 * personnel. Les bornes de la journée sont calculées dans le fuseau du service.
 */
@SpringService
class DayLineConsoleService(
    private val services: ServiceAdminService,
    private val config: ServiceConfigService,
    private val ticketing: TicketingModuleApi,
    private val events: ApplicationEventPublisher,
) {
    /** La journée demandée ([date] ou aujourd'hui dans le fuseau du service). */
    @Transactional(readOnly = true)
    fun view(
        serviceId: UUID,
        date: LocalDate?,
    ): DayLineView {
        val service = services.get(serviceId)
        val zone = ZoneId.of(service.timezone)
        val day = date ?: LocalDate.now(zone)
        val (start, end) = window(zone, day)
        return DayLineView(
            serviceId = service.id,
            serviceName = service.name,
            timezone = service.timezone,
            date = day,
            entries = ticketing.serviceLine(serviceId, start, end),
        )
    }

    /** Appelle la personne suivante sur la ligne d'aujourd'hui (règle §4.1). */
    @Transactional
    fun next(serviceId: UUID): LineTicket? {
        val today = today(serviceId)
        val tolerance = config.effective(serviceId).noShowToleranceMinutes.toLong()
        return ticketing.callNext(serviceId, today.start, today.end, Instant.now(), tolerance)
    }

    /** Arrivée au comptoir d'un rendez-vous d'aujourd'hui. */
    @Transactional
    fun arrive(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult {
        val today = today(serviceId)
        return ticketing.arriveByCode(serviceId, ticketCode, Instant.now(), today.start, today.end, today.day)
    }

    /** Appel d'une personne précise de la ligne d'aujourd'hui. */
    @Transactional
    fun call(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult = ticketing.callByCode(serviceId, ticketCode)

    /** Prise en charge d'une personne appelée. */
    @Transactional
    fun present(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult = ticketing.presentByCode(serviceId, ticketCode)

    /** Fin de la prise en charge. */
    @Transactional
    fun finish(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult = ticketing.finishByCode(serviceId, ticketCode)

    /** Absent après appel. */
    @Transactional
    fun noShow(
        serviceId: UUID,
        ticketCode: String,
    ): LineActionResult = ticketing.noShowByCode(serviceId, ticketCode)

    /**
     * Inscrit un sans-rendez-vous au comptoir : le client est déjà présent, son
     * billet naît en attente avec son rang. L'invité et le billet sont
     * matérialisés dans la même transaction par l'écouteur de l'événement
     * [WalkInArrived]. Renvoie la ligne actualisée.
     */
    @Transactional
    fun walkIn(
        serviceId: UUID,
        clientName: String,
        clientPhone: String,
    ): DayLineView {
        val service = services.get(serviceId)
        if (!config.effective(serviceId).walkInsAllowed) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This service does not accept walk-ins")
        }
        val tenantId = TenantContext.get() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant")
        val zone = ZoneId.of(service.timezone)
        val now = Instant.now()
        val day = LocalDate.now(zone)
        val (start, end) = window(zone, day)
        events.publishEvent(
            WalkInArrived(
                serviceId = serviceId,
                tenantId = tenantId,
                clientName = clientName.trim(),
                clientPhone = clientPhone.trim(),
                professionalName = null,
                arrivedAt = now,
                dayStart = start,
                dayEnd = end,
                rankDay = day,
            ),
        )
        return view(serviceId, day)
    }

    private fun today(serviceId: UUID): DayWindow {
        val service = services.get(serviceId)
        val zone = ZoneId.of(service.timezone)
        val day = LocalDate.now(zone)
        val (start, end) = window(zone, day)
        return DayWindow(day, start, end)
    }

    private data class DayWindow(
        val day: LocalDate,
        val start: Instant,
        val end: Instant,
    )

    private fun window(
        zone: ZoneId,
        day: LocalDate,
    ): Pair<Instant, Instant> = day.atStartOfDay(zone).toInstant() to day.plusDays(1).atStartOfDay(zone).toInstant()
}
