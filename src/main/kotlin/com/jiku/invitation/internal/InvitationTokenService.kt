package com.jiku.invitation.internal

import com.jiku.shared.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Issues and verifies the signed, stateless token embedded in a guest's
 * invitation link. The token carries the guest and event ids and is verified by
 * the guest-facing RSVP flow (JIKU-19); nothing is stored server-side.
 */
@Service
class InvitationTokenService(
    properties: JwtProperties,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(properties.secret.toByteArray())

    fun issue(
        guestId: UUID,
        eventId: UUID,
    ): String =
        Jwts
            .builder()
            .subject(guestId.toString())
            .claim(CLAIM_EVENT_ID, eventId.toString())
            .claim(CLAIM_TYPE, TOKEN_TYPE)
            .signWith(key)
            .compact()

    fun parse(token: String): Claims =
        Jwts
            .parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

    companion object {
        const val CLAIM_EVENT_ID = "eventId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "invitation"
    }
}
