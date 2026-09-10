package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
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
)

data class AppointmentSlotView(
    val startsAt: Instant,
    val endsAt: Instant,
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
) {
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
        return AppointmentServiceView(
            serviceId = service.id,
            name = service.name,
            timezone = service.timezone,
            confirmationMode = config.effective(service.id).confirmationMode.name,
            professionals = professionals,
            slots = engine.openSlots(serviceId, day).map { AppointmentSlotView(it.startsAt, it.endsAt) },
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
