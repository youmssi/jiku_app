package com.jiku.invitation.internal

import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import io.jsonwebtoken.Claims
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.UUID

/**
 * Guest-facing RSVP endpoints, authenticated by the signed invitation token in the
 * path rather than a login. The token carries the tenant, which is bound to the
 * request before the transactional service runs so tenant-scoped queries resolve.
 */
@RestController
@RequestMapping("/rsvp")
class RsvpController(
    private val tokenService: InvitationTokenService,
    private val rsvpService: RsvpService,
    private val erasureService: GuestErasureService,
    private val dispatcher: InvitationDispatcher,
    private val tenantAccessGate: TenantAccessGate,
) {
    @GetMapping("/{token}")
    fun view(
        @PathVariable token: String,
    ): RsvpView = withTokenContext(token) { guestId, _ -> rsvpService.view(guestId) }

    /**
     * Guest self-service right to erasure (JIKU-36). Anonymizes the guest's personal
     * data and returns the (now anonymized) view. The confirmation/irreversibility
     * warning is enforced in the UI; this endpoint performs the deletion.
     */
    @PostMapping("/{token}/erase")
    fun erase(
        @PathVariable token: String,
    ): RsvpView =
        withTokenContext(token) { guestId, _ ->
            erasureService.eraseGuest(guestId, ErasureReason.GUEST_REQUEST)
            rsvpService.view(guestId)
        }

    /**
     * The ticket's QR code as a PNG (JIKU-143), the image of the WhatsApp ticket
     * message: Meta fetches it by URL. Only a guest holding a ticket has one.
     */
    @GetMapping("/{token}/qr.png", produces = [MediaType.IMAGE_PNG_VALUE])
    fun qrCode(
        @PathVariable token: String,
    ): ResponseEntity<ByteArray> {
        val code =
            withTokenContext(token) { guestId, _ -> rsvpService.view(guestId).ticketCode }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No ticket for this invitation")
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePrivate())
            .body(QrCodeImage.png(code))
    }

    @PostMapping("/{token}/confirm")
    fun confirm(
        @PathVariable token: String,
    ): RsvpView = withTokenContext(token) { guestId, eventId -> rsvpService.confirm(guestId, eventId) }

    @PostMapping("/{token}/decline")
    fun decline(
        @PathVariable token: String,
    ): RsvpView = withTokenContext(token) { guestId, eventId -> rsvpService.decline(guestId, eventId) }

    /**
     * Hands this guest's place to someone else (JIKU-64). Dispatch of the
     * recipient's own invitation runs after the transfer commits, following the
     * same pattern as the organizer's send.
     */
    @PostMapping("/{token}/transfer")
    fun transfer(
        @PathVariable token: String,
        @Valid @RequestBody request: TransferTicketRequest,
    ): RsvpView =
        withTokenContext(token) { guestId, eventId ->
            val view = rsvpService.transfer(guestId, eventId, request)
            // The transfer transaction has committed by now, so the async
            // dispatcher sees the recipient's queued invitation.
            dispatcher.dispatchPending(eventId)
            view
        }

    private fun <T> withTokenContext(
        token: String,
        block: (UUID, UUID) -> T,
    ): T {
        val claims = parse(token)
        if (claims[InvitationTokenService.CLAIM_TYPE] != InvitationTokenService.TOKEN_TYPE) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This invitation link is invalid")
        }
        val tenantId =
            claims[InvitationTokenService.CLAIM_TENANT_ID] as? String
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This invitation link is invalid")
        if (tenantAccessGate.isSuspended(tenantId)) {
            // Platform-level suspension (JIKU-40): the organizer's links stop
            // resolving; a plain not-found leaks nothing about the reason.
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This invitation link is no longer available")
        }
        TenantContext.set(tenantId)
        try {
            val guestId = UUID.fromString(claims.subject)
            val eventId = UUID.fromString(claims[InvitationTokenService.CLAIM_EVENT_ID] as String)
            return block(guestId, eventId)
        } finally {
            TenantContext.clear()
        }
    }

    private fun parse(token: String): Claims =
        try {
            tokenService.parse(token)
        } catch (ex: RuntimeException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This invitation link is invalid or has expired", ex)
        }
}
