package com.jiku.catalog.internal

import com.jiku.shared.ClientCalled
import com.jiku.shared.ReminderChannel
import com.jiku.shared.TenantContext
import com.jiku.shared.WalkInArrived
import com.jiku.ticket.LineActionResult
import com.jiku.ticket.LineOutcome
import com.jiku.ticket.LineTicket
import com.jiku.ticket.TicketInfo
import com.jiku.ticket.TicketPaymentMethod
import com.jiku.ticket.TicketPaymentOutcome
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
    fun next(
        serviceId: UUID,
        counter: String? = null,
    ): LineTicket? {
        val today = today(serviceId)
        val tolerance = config.effective(serviceId).noShowToleranceMinutes.toLong()
        return ticketing
            .callNext(serviceId, today.start, today.end, Instant.now(), tolerance, counterLabel(counter))
            ?.also { announceCall(serviceId, it) }
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
        counter: String? = null,
    ): LineActionResult =
        ticketing.callByCode(serviceId, ticketCode, counterLabel(counter)).also { result ->
            if (result.outcome == LineOutcome.OK) result.ticket?.let { announceCall(serviceId, it) }
        }

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

    /**
     * Records that the client paid the organization (JIKU-110), attributed to
     * [paidBy]. Only a ticket of this service's line can be marked here.
     */
    @Transactional
    fun markPaid(
        serviceId: UUID,
        ticketCode: String,
        method: TicketPaymentMethod,
        paidBy: String,
    ): TicketInfo {
        ticketing.findByCode(ticketCode)?.takeIf { it.serviceId == serviceId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No line entry with this code on this service")
        val result = ticketing.markPaidByCode(ticketCode, method, paidBy)
        if (result.outcome != TicketPaymentOutcome.PAID) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Nothing is owed on this ticket, or it is already paid")
        }
        return requireNotNull(result.ticket)
    }

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
                charge = services.clientCharge(serviceId),
            ),
        )
        return view(serviceId, day)
    }

    /** Tells the called client it is their turn, through the service's client channel, if it has one. */
    private fun announceCall(
        serviceId: UUID,
        called: LineTicket,
    ) {
        val channel = config.effective(serviceId).reminderChannel
        val phone = called.clientPhone
        if (channel == ReminderChannel.NONE || phone == null) return
        val tenantId = TenantContext.get() ?: return
        events.publishEvent(
            ClientCalled(
                ticketId = called.id,
                tenantId = tenantId,
                clientName = called.clientName,
                clientPhone = phone,
                counter = called.counter,
                channel = channel,
            ),
        )
    }

    /** The counter shown to the called client ("counter 4"); blank means none. */
    private fun counterLabel(counter: String?): String? {
        val label = counter?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (label.length > MAX_COUNTER_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A counter name is at most $MAX_COUNTER_LENGTH characters")
        }
        return label
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

private const val MAX_COUNTER_LENGTH = 40

/**
 * Turns a line transition's outcome into the reply of both counters, the
 * organizer's and the staff link's: the entry itself, or a clear refusal.
 */
internal fun LineActionResult.orThrow(): LineActionResult =
    when (outcome) {
        LineOutcome.OK -> this
        LineOutcome.NOT_FOUND ->
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "No line entry with this code on this service")
        LineOutcome.WRONG_STATE ->
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "This entry is no longer in the expected state — it may have been handled by another desk",
            )
        LineOutcome.PAYMENT_DUE ->
            throw ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "The client has not paid yet; record the payment first")
    }
