package com.jiku.shared

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.Date
import javax.crypto.SecretKey

/**
 * Issues and verifies the application's JWTs. Access and refresh tokens are both
 * signed HMAC-SHA tokens distinguished by a token-type claim; refresh tokens are
 * stateless (no server-side store), so revocation is intentionally out of scope
 * for the MVP.
 */
@Service
class JwtService(
    properties: JwtProperties,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(properties.secret.toByteArray())
    private val accessExpiration = properties.accessTokenExpiration
    private val refreshExpiration = properties.refreshTokenExpiration

    fun generateAccessToken(
        userId: String,
        tenantId: String,
        role: String,
    ): String = build(userId, tenantId, role, TOKEN_TYPE_ACCESS, accessExpiration)

    fun generateRefreshToken(
        userId: String,
        tenantId: String,
        role: String,
    ): String = build(userId, tenantId, role, TOKEN_TYPE_REFRESH, refreshExpiration)

    fun parse(token: String): Claims =
        Jwts
            .parser()
            .verifyWith(key)
            .build()
            .parseSignedClaims(token)
            .payload

    /**
     * Generic signed token for non-session flows (invitation, service link, day
     * line, validator): one place owns the secret key and the HMAC builder so the
     * per-flow token services only describe their claims. [ttl] null means no
     * expiration, which is the deliberate policy for long-lived invitation links.
     */
    fun sign(
        subject: String,
        claims: Map<String, Any?>,
        ttl: java.time.Duration? = null,
    ): String {
        val now = Instant.now()
        val builder = Jwts.builder().subject(subject)
        claims.forEach { (name, value) -> if (value != null) builder.claim(name, value) }
        builder.issuedAt(Date.from(now))
        if (ttl != null) {
            builder.expiration(Date.from(now.plus(ttl)))
        }
        return builder.signWith(key).compact()
    }

    private fun build(
        userId: String,
        tenantId: String,
        role: String,
        type: String,
        ttl: java.time.Duration,
    ): String {
        val now = Instant.now()
        return Jwts
            .builder()
            .subject(userId)
            .claim(CLAIM_TENANT_ID, tenantId)
            .claim(CLAIM_ROLE, role)
            .claim(CLAIM_TOKEN_TYPE, type)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(ttl)))
            .signWith(key)
            .compact()
    }

    companion object {
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_ROLE = "role"
        const val CLAIM_TOKEN_TYPE = "tokenType"
        const val TOKEN_TYPE_ACCESS = "access"
        const val TOKEN_TYPE_REFRESH = "refresh"
    }
}
