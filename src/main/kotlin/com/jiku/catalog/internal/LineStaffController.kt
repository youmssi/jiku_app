package com.jiku.catalog.internal

import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import com.jiku.ticket.LineActionResult
import com.jiku.ticket.LineOutcome
import io.jsonwebtoken.Claims
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.LocalDate
import java.util.UUID

/**
 * Console de ligne du jour pour le personnel, authentifié par le lien signé dans
 * le chemin plutôt que par un compte (JIKU-88). Le jeton porte le tenant, le
 * service et l'identité de la ligne [ServiceStaff] ; chaque requête le résout,
 * rejette un lien révoqué ou suspendu, puis sert la même console que
 * l'organisateur.
 */
@RestController
@RequestMapping("/line/{token}")
class LineStaffController(
    private val tokens: DayLineTokenService,
    private val staff: ServiceStaffRepository,
    private val console: DayLineConsoleService,
    private val requests: AppointmentRequestService,
    private val tenantAccessGate: TenantAccessGate,
) {
    @GetMapping
    fun view(
        @PathVariable token: String,
        @RequestParam(required = false) date: String? = null,
    ): DayLineView = withStaff(token) { serviceId -> console.view(serviceId, parseDate(date)) }

    /** Demandes de rendez-vous en attente de confirmation (mode « sur demande »). */
    @GetMapping("/requests")
    fun pendingRequests(
        @PathVariable token: String,
        @RequestParam(required = false) date: String? = null,
    ): List<PendingAppointmentRequest> = withStaff(token) { serviceId -> requests.pending(serviceId, parseDate(date)) }

    /** Confirme une demande en attente : le rendez-vous et son billet sont émis. */
    @PostMapping("/requests/{requestId}/accept")
    fun acceptRequest(
        @PathVariable token: String,
        @PathVariable requestId: UUID,
    ): Unit = withStaff(token) { serviceId -> requests.accept(serviceId, requestId) }

    /** Refuse une demande en attente : le créneau se libère. */
    @PostMapping("/requests/{requestId}/reject")
    fun rejectRequest(
        @PathVariable token: String,
        @PathVariable requestId: UUID,
    ): Unit = withStaff(token) { serviceId -> requests.reject(serviceId, requestId) }

    @PostMapping("/next")
    fun next(
        @PathVariable token: String,
    ): NextResponse = withStaff(token) { serviceId -> NextResponse(console.next(serviceId)) }

    @PostMapping("/walk-in")
    @ResponseStatus(HttpStatus.CREATED)
    fun walkIn(
        @PathVariable token: String,
        @Valid @RequestBody request: WalkInRequest,
    ): DayLineView = withStaff(token) { serviceId -> console.walkIn(serviceId, request.clientName, request.clientPhone) }

    @PostMapping("/tickets/{ticketCode}/arrive")
    fun arrive(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
    ): LineActionResult = withStaff(token) { serviceId -> reply(console.arrive(serviceId, ticketCode)) }

    @PostMapping("/tickets/{ticketCode}/call")
    fun call(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
    ): LineActionResult = withStaff(token) { serviceId -> reply(console.call(serviceId, ticketCode)) }

    @PostMapping("/tickets/{ticketCode}/present")
    fun present(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
    ): LineActionResult = withStaff(token) { serviceId -> reply(console.present(serviceId, ticketCode)) }

    @PostMapping("/tickets/{ticketCode}/finish")
    fun finish(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
    ): LineActionResult = withStaff(token) { serviceId -> reply(console.finish(serviceId, ticketCode)) }

    @PostMapping("/tickets/{ticketCode}/no-show")
    fun noShow(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
    ): LineActionResult = withStaff(token) { serviceId -> reply(console.noShow(serviceId, ticketCode)) }

    private fun reply(result: LineActionResult): LineActionResult =
        when (result.outcome) {
            LineOutcome.OK -> result
            LineOutcome.NOT_FOUND ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "No line entry with this code on this service")
            LineOutcome.WRONG_STATE ->
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This entry is no longer in the expected state — it may have been handled by another desk",
                )
        }

    private fun <T> withStaff(
        token: String,
        block: (UUID) -> T,
    ): T {
        val claims = parse(token)
        val tenantId =
            claims[DayLineTokenService.CLAIM_TENANT_ID] as? String
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link is invalid")
        if (tenantAccessGate.isSuspended(tenantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link is no longer available")
        }
        TenantContext.set(tenantId)
        try {
            val staffId = UUID.fromString(claims.subject)
            val serviceId =
                UUID.fromString(claims[DayLineTokenService.CLAIM_SERVICE_ID] as String)
            val row =
                staff.findById(staffId).orElse(null)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link has been revoked")
            if (row.revoked || row.serviceId != serviceId) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link has been revoked")
            }
            return block(serviceId)
        } catch (ex: IllegalArgumentException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link is invalid", ex)
        } finally {
            TenantContext.clear()
        }
    }

    private fun parse(token: String): Claims =
        try {
            tokens.parse(token)
        } catch (ex: RuntimeException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This counter link is invalid or has expired", ex)
        }

    private fun parseDate(date: String?): LocalDate? =
        date?.let {
            try {
                LocalDate.parse(it)
            } catch (ex: RuntimeException) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Date invalide : $it")
            }
        }
}
