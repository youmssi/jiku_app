package com.jiku.checkin.internal

import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import com.jiku.ticket.MarkPaidRequest
import com.jiku.ticket.TicketInfo
import io.jsonwebtoken.Claims
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Validator-facing check-in endpoints, authenticated by the signed validator link
 * in the path rather than a login. The token carries the tenant and event, which
 * are bound and resolved (rejecting a revoked link) before each action; check-ins
 * are attributed to the link's label.
 */
@RestController
@RequestMapping("/checkin/{token}")
class ValidatorCheckInController(
    private val tokenService: ValidatorTokenService,
    private val validatorService: ValidatorService,
    private val checkInService: CheckInService,
    private val tenantAccessGate: TenantAccessGate,
) {
    @GetMapping
    fun context(
        @PathVariable token: String,
    ): ValidatorContextResponse = withValidator(token) { eventId, label -> checkInService.context(eventId, label) }

    @PostMapping("/scan")
    fun scan(
        @PathVariable token: String,
        @Valid @RequestBody request: ScanRequest,
    ): CheckInResponse =
        withValidator(token) { eventId, label ->
            checkInService.checkInByCode(eventId, request.ticketCode, label)
        }

    @PostMapping("/manual")
    fun manual(
        @PathVariable token: String,
        @Valid @RequestBody request: ManualCheckInRequest,
    ): CheckInResponse =
        withValidator(token) { eventId, label ->
            checkInService.checkInByGuest(eventId, request.guestId, label)
        }

    @PostMapping("/tickets/{ticketCode}/paid")
    fun markPaid(
        @PathVariable token: String,
        @PathVariable ticketCode: String,
        @RequestBody request: MarkPaidRequest,
    ): TicketInfo = withValidator(token) { eventId, label -> checkInService.markPaid(eventId, ticketCode, request.method, label) }

    @GetMapping("/search")
    fun search(
        @PathVariable token: String,
        @RequestParam("q") query: String,
    ): List<GuestMatch> = withValidator(token) { eventId, _ -> checkInService.search(eventId, query) }

    @GetMapping("/stats")
    fun stats(
        @PathVariable token: String,
    ): AttendanceResponse = withValidator(token) { eventId, _ -> checkInService.stats(eventId) }

    @GetMapping("/roster")
    fun roster(
        @PathVariable token: String,
    ): List<RosterEntry> = withValidator(token) { eventId, _ -> checkInService.roster(eventId) }

    @PostMapping("/sync")
    fun sync(
        @PathVariable token: String,
        @Valid @RequestBody request: SyncRequest,
    ): List<SyncResultEntry> = withValidator(token) { eventId, label -> checkInService.sync(eventId, label, request.items) }

    private fun <T> withValidator(
        token: String,
        block: (UUID, String) -> T,
    ): T {
        val claims = parse(token)
        val tenantId =
            claims[ValidatorTokenService.CLAIM_TENANT_ID] as? String
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This check-in link is invalid")
        if (tenantAccessGate.isSuspended(tenantId)) {
            // Platform-level suspension (JIKU-40): validator links stop resolving.
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This check-in link is no longer available")
        }
        TenantContext.set(tenantId)
        try {
            val validatorId = UUID.fromString(claims.subject)
            val eventId = UUID.fromString(claims[ValidatorTokenService.CLAIM_EVENT_ID] as String)
            val label = validatorService.resolveActiveLabel(validatorId, eventId)
            return block(eventId, label)
        } finally {
            TenantContext.clear()
        }
    }

    private fun parse(token: String): Claims =
        try {
            tokenService.parse(token)
        } catch (ex: RuntimeException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This check-in link is invalid or has expired", ex)
        }
}
