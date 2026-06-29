package com.jiku.checkin.internal

import com.jiku.shared.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Issues and verifies the signed token embedded in a validator access link. The
 * token carries the validator and event ids; it is verified statelessly, while the
 * validator row (looked up by the token's subject) governs revocation. A separate
 * token type prevents an invitation or access token from being used as a check-in
 * link.
 */
@Service
class ValidatorTokenService(
    jwtProperties: JwtProperties,
    private val properties: ValidatorLinkProperties,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())

    fun issue(
        validatorId: UUID,
        eventId: UUID,
        tenantId: String,
    ): String {
        val now = Instant.now()
        return Jwts
            .builder()
            .subject(validatorId.toString())
            .claim(CLAIM_EVENT_ID, eventId.toString())
            .claim(CLAIM_TENANT_ID, tenantId)
            .claim(CLAIM_TYPE, TOKEN_TYPE)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(properties.validity)))
            .signWith(key)
            .compact()
    }

    fun parse(token: String): Claims {
        val claims =
            Jwts
                .parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
        require(claims[CLAIM_TYPE] == TOKEN_TYPE) { "Not a validator token" }
        return claims
    }

    companion object {
        const val CLAIM_EVENT_ID = "eventId"
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "validator"
    }
}
