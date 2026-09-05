package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import io.jsonwebtoken.Claims
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
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
 * Entrée publique du parcours client rendez-vous (JIKU-87), sans compte. Le lien
 * de service signé porte le tenant et le service ; il est résolu ici (suspension
 * et type vérifiés), le contexte tenant est lié le temps de la requête, puis le
 * client consulte le service et ses créneaux, réserve, et consulte ou annule sa
 * réservation par son jeton.
 */
@RestController
@RequestMapping("/appointments")
class AppointmentPublicController(
    private val linkTokens: ServiceLinkTokenService,
    private val services: ServiceAdminService,
    private val config: ServiceConfigService,
    private val engine: SlotEngine,
    private val resources: ResourceRepository,
    private val reservations: ServiceReservationRepository,
    private val tenantAccessGate: TenantAccessGate,
    private val cancellations: AppointmentCancellationService,
) {
    @GetMapping("/{token}")
    fun view(
        @PathVariable token: String,
        @RequestParam(required = false) date: String?,
    ): AppointmentServiceView =
        withService(token) { serviceId ->
            val service = services.get(serviceId)
            val zone = ZoneId.of(service.timezone)
            val day = date?.let { LocalDate.parse(it) } ?: LocalDate.now(zone)
            val professionals = resources.findByActiveTrueAndTypeOrderByNameAsc(ResourceType.PERSON).map { it.name }
            AppointmentServiceView(
                serviceId = service.id,
                name = service.name,
                timezone = service.timezone,
                confirmationMode = config.effective(service.id).confirmationMode.name,
                professionals = professionals,
                slots = engine.openSlots(serviceId, day).map { AppointmentSlotView(it.startsAt, it.endsAt) },
            )
        }

    @PostMapping("/{token}/book")
    @ResponseStatus(HttpStatus.CREATED)
    fun book(
        @PathVariable token: String,
        @Valid @RequestBody request: AppointmentBookingRequest,
    ): AppointmentBookingView =
        withService(token) { serviceId ->
            try {
                val outcome = engine.bookClient(serviceId, request.startsAt, request.clientName, request.clientPhone)
                AppointmentBookingView(
                    bookingToken = outcome.bookingToken,
                    status = outcome.status.name,
                    startsAt = outcome.startsAt,
                    endsAt = outcome.endsAt,
                )
            } catch (ex: SlotUnavailableException) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Ce créneau n'est plus disponible", ex)
            }
        }

    @GetMapping("/{token}/booking/{bookingToken}")
    fun status(
        @PathVariable token: String,
        @PathVariable bookingToken: String,
    ): AppointmentStatusView =
        withService(token) { _ ->
            val rows = byBookingToken(bookingToken)
            val first = rows.first()
            AppointmentStatusView(
                status = first.status.name,
                startsAt = first.startsAt,
                endsAt = first.endsAt,
                clientName = first.clientName,
            )
        }

    @DeleteMapping("/{token}/booking/{bookingToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(
        @PathVariable token: String,
        @PathVariable bookingToken: String,
    ) {
        withService(token) { _ -> cancellations.cancel(bookingToken) }
    }

    private fun byBookingToken(raw: String): List<ServiceReservation> {
        val rows = reservations.findByBookingTokenHash(BookingToken.hash(raw))
        if (rows.isEmpty()) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Cette réservation est introuvable")
        }
        return rows
    }

    private fun <T> withService(
        token: String,
        block: (UUID) -> T,
    ): T {
        val claims = parse(token)
        if (claims[ServiceLinkTokenService.CLAIM_TYPE] != ServiceLinkTokenService.TOKEN_TYPE) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid")
        }
        val tenantId =
            claims[ServiceLinkTokenService.CLAIM_TENANT_ID] as? String
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid")
        if (tenantAccessGate.isSuspended(tenantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is no longer available")
        }
        TenantContext.set(tenantId)
        try {
            val serviceId = UUID.fromString(claims.subject)
            return block(serviceId)
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid", ex)
        } finally {
            TenantContext.clear()
        }
    }

    private fun parse(token: String): Claims =
        try {
            linkTokens.parse(token)
        } catch (ex: RuntimeException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is invalid or has expired", ex)
        }
}
