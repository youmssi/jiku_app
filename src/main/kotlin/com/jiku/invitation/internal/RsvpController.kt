package com.jiku.invitation.internal

import com.jiku.shared.TenantContext
import io.jsonwebtoken.Claims
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
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
) {
    @GetMapping("/{token}")
    fun view(
        @PathVariable token: String,
    ): RsvpView = withTokenContext(token) { guestId, _ -> rsvpService.view(guestId) }

    @PostMapping("/{token}/confirm")
    fun confirm(
        @PathVariable token: String,
    ): RsvpView = withTokenContext(token) { guestId, eventId -> rsvpService.confirm(guestId, eventId) }

    @PostMapping("/{token}/decline")
    fun decline(
        @PathVariable token: String,
    ): RsvpView = withTokenContext(token) { guestId, eventId -> rsvpService.decline(guestId, eventId) }

    private fun withTokenContext(
        token: String,
        block: (UUID, UUID) -> RsvpView,
    ): RsvpView {
        val claims = parse(token)
        val tenantId =
            claims[InvitationTokenService.CLAIM_TENANT_ID] as? String
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This invitation link is invalid")
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
