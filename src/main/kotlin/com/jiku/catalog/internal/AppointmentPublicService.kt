package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import com.jiku.ticket.LineTicket
import com.jiku.ticket.TicketPaymentStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class AppointmentServiceView(
    val serviceId: UUID,
    val name: String,
    val timezone: String,
    val confirmationMode: String,
    val professionals: List<String>,
    val slots: List<AppointmentSlotView>,
    /** Clients served together per slot; above 1 the service runs group sessions (JIKU-174). */
    val clientsPerSlot: Int = 1,
)

data class AppointmentSlotView(
    val startsAt: Instant,
    val endsAt: Instant,
    /** Clients the slot can still take. */
    val placesLeft: Int = 1,
)

data class AppointmentBookingRequest(
    @field:NotBlank @field:Size(max = 120) val clientName: String,
    @field:NotBlank val clientPhone: String,
    val startsAt: Instant,
)

data class AppointmentBookingView(
    val bookingToken: String,
    val status: String,
    val startsAt: Instant,
    val endsAt: Instant,
)

/**
 * A client's own place in the day's line (JIKU-113): counts only, never who else
 * is waiting.
 */
data class ClientLineTicketView(
    val ticketCode: String,
    val status: String,
    val dayRank: Int?,
    /** Clients who arrived earlier and still wait; an appointment due meanwhile may pass first. */
    val peopleAhead: Int,
    val estimatedWaitMinutes: Long,
    /** The counter to go to, once called. */
    val counter: String?,
    val paymentStatus: TicketPaymentStatus,
    val amountDueMinor: Long?,
    val amountDueCurrency: String?,
)

data class AppointmentStatusView(
    val status: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val clientName: String?,
)

/**
 * Cœur du parcours public rendez-vous (JIKU-87), partagé entre l'entrée par
 * jeton signé (/appointments/{token}) et l'entrée par lien court (/r/{code}) :
 * les deux résolvent un (tenant, service), vérifient la suspension, lient le
 * contexte tenant le temps de la requête, puis exécutent la même logique.
 */
@Service
class AppointmentPublicService(
    private val linkTokens: ServiceLinkTokenService,
    private val serviceLinks: ServiceLinkCodeService,
    private val services: ServiceAdminService,
    private val config: ServiceConfigService,
    private val engine: SlotEngine,
    private val resources: ResourceRepository,
    private val reservations: ServiceReservationRepository,
    private val tenantAccessGate: TenantAccessGate,
    private val cancellations: AppointmentCancellationService,
    private val line: DayLineConsoleService,
) {
    /** A client takes a ticket for today's line, from the QR shown at the entrance (JIKU-113). */
    fun takeTicketByCode(
        code: String,
        request: WalkInRequest,
    ): ClientLineTicketView = withService(resolveCode(code), null) { serviceId -> takeTicket(serviceId, request) }

    fun takeTicketByToken(
        token: String,
        request: WalkInRequest,
    ): ClientLineTicketView = withService(resolveToken(token), null) { serviceId -> takeTicket(serviceId, request) }

    fun lineTicketByCode(
        code: String,
        ticketCode: String,
    ): ClientLineTicketView = withService(resolveCode(code), null) { serviceId -> lineTicket(serviceId, ticketCode) }

    fun lineTicketByToken(
        token: String,
        ticketCode: String,
    ): ClientLineTicketView = withService(resolveToken(token), null) { serviceId -> lineTicket(serviceId, ticketCode) }

    fun viewByToken(
        token: String,
        date: String?,
    ): AppointmentServiceView = withService(resolveToken(token), date) { serviceId -> view(serviceId, date) }

    fun viewByCode(
        code: String,
        date: String?,
    ): AppointmentServiceView = withService(resolveCode(code), date) { serviceId -> view(serviceId, date) }

    fun bookByToken(
        token: String,
        request: AppointmentBookingRequest,
    ): AppointmentBookingView = withService(resolveToken(token), null) { serviceId -> book(serviceId, request) }

    fun bookByCode(
        code: String,
        request: AppointmentBookingRequest,
    ): AppointmentBookingView = withService(resolveCode(code), null) { serviceId -> book(serviceId, request) }

    fun statusByToken(
        token: String,
        bookingToken: String,
    ): AppointmentStatusView = withService(resolveToken(token), null) { status(bookingToken) }

    fun statusByCode(
        code: String,
        bookingToken: String,
    ): AppointmentStatusView = withService(resolveCode(code), null) { status(bookingToken) }

    fun cancelByToken(
        token: String,
        bookingToken: String,
    ) {
        withService(resolveToken(token), null) { cancellations.cancel(bookingToken) }
    }

    fun cancelByCode(
        code: String,
        bookingToken: String,
    ) {
        withService(resolveCode(code), null) { cancellations.cancel(bookingToken) }
    }

    private fun view(
        serviceId: UUID,
        date: String?,
    ): AppointmentServiceView {
        val service = services.get(serviceId)
        val zone = ZoneId.of(service.timezone)
        val day = date?.let { LocalDate.parse(it) } ?: LocalDate.now(zone)
        val professionals = resources.findByActiveTrueAndTypeOrderByNameAsc(ResourceType.PERSON).map { it.name }
        val effective = config.effective(service.id)
        return AppointmentServiceView(
            serviceId = service.id,
            name = service.name,
            timezone = service.timezone,
            confirmationMode = effective.confirmationMode.name,
            professionals = professionals,
            slots = engine.openSlots(serviceId, day).map { AppointmentSlotView(it.startsAt, it.endsAt, it.placesLeft) },
            clientsPerSlot = effective.clientsPerSlot,
        )
    }

    private fun book(
        serviceId: UUID,
        request: AppointmentBookingRequest,
    ): AppointmentBookingView =
        try {
            val outcome = engine.bookClient(serviceId, request.startsAt, request.clientName, request.clientPhone)
            AppointmentBookingView(
                bookingToken = outcome.bookingToken,
                status = outcome.status.name,
                startsAt = outcome.startsAt,
                endsAt = outcome.endsAt,
            )
        } catch (ex: SlotUnavailableException) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This slot is no longer available", ex)
        }

    private fun status(bookingToken: String): AppointmentStatusView {
        val rows = byBookingToken(bookingToken)
        val first = rows.first()
        return AppointmentStatusView(
            status = first.status.name,
            startsAt = first.startsAt,
            endsAt = first.endsAt,
            clientName = first.clientName,
        )
    }

    private fun byBookingToken(raw: String): List<ServiceReservation> {
        val rows = reservations.findByBookingTokenHash(BookingToken.hash(raw))
        if (rows.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This booking was not found")
        }
        return rows
    }

    private fun takeTicket(
        serviceId: UUID,
        request: WalkInRequest,
    ): ClientLineTicketView {
        val phone = request.clientPhone.trim()
        val entries = line.walkIn(serviceId, request.clientName, phone).entries
        // Issued in this transaction: the newest walk-in of this phone is the one just taken.
        val mine =
            entries.filter { it.kind == WALK_IN && it.clientPhone == phone }.maxBy { it.dayRank ?: 0 }
        return placeOf(serviceId, mine, entries)
    }

    private fun lineTicket(
        serviceId: UUID,
        ticketCode: String,
    ): ClientLineTicketView {
        val entries = line.view(serviceId, null).entries
        val mine =
            entries.firstOrNull { it.ticketCode == ticketCode }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This ticket is not in today's line")
        return placeOf(serviceId, mine, entries)
    }

    private fun placeOf(
        serviceId: UUID,
        mine: LineTicket,
        entries: List<LineTicket>,
    ): ClientLineTicketView {
        val arrived = mine.arrivedAt
        val ahead =
            if (mine.status != WAITING || arrived == null) {
                0
            } else {
                entries.count { it.status == WAITING && it.id != mine.id && it.arrivedAt?.isBefore(arrived) == true }
            }
        return ClientLineTicketView(
            ticketCode = mine.ticketCode,
            status = mine.status,
            dayRank = mine.dayRank,
            peopleAhead = ahead,
            estimatedWaitMinutes = ahead * config.effective(serviceId).occupancyMinutes,
            counter = mine.counter,
            paymentStatus = mine.paymentStatus,
            amountDueMinor = mine.amountDueMinor,
            amountDueCurrency = mine.amountDueCurrency,
        )
    }

    private fun resolveToken(token: String): ResolvedLink {
        val claims =
            try {
                linkTokens.parse(token)
            } catch (ex: RuntimeException) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid or has expired", ex)
            }
        if (claims[ServiceLinkTokenService.CLAIM_TYPE] != ServiceLinkTokenService.TOKEN_TYPE) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid")
        }
        val tenantId =
            claims[ServiceLinkTokenService.CLAIM_TENANT_ID] as? String
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid")
        return ResolvedLink(UUID.fromString(claims.subject), tenantId)
    }

    private fun resolveCode(code: String): ResolvedLink {
        val link =
            serviceLinks.byCode(code.uppercase())
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid")
        return ResolvedLink(link.serviceId, link.tenantId)
    }

    private fun <T> withService(
        resolved: ResolvedLink,
        date: String?,
        block: (UUID) -> T,
    ): T {
        if (tenantAccessGate.isSuspended(resolved.tenantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is no longer available")
        }
        TenantContext.set(resolved.tenantId)
        try {
            return block(resolved.serviceId)
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid", ex)
        } finally {
            TenantContext.clear()
        }
    }

    private data class ResolvedLink(
        val serviceId: UUID,
        val tenantId: String,
    )
}

private const val WALK_IN = "WALK_IN"
private const val WAITING = "WAITING"
