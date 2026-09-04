package com.jiku.catalog.internal

import com.jiku.shared.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.UUID
import javax.crypto.SecretKey
import org.springframework.stereotype.Service as SpringService

/**
 * Émet et vérifie le lien de service signé (JIKU-87) : l'organisateur le partage,
 * le client s'en sert sans compte pour voir le service et ses créneaux puis
 * réserver. Le jeton porte le tenant et le service ; rien n'est stocké côté
 * serveur, il est vérifié par le flux public sous /appointments/{token}.
 */
@SpringService
class ServiceLinkTokenService(
    properties: JwtProperties,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(properties.secret.toByteArray())

    fun issue(
        serviceId: UUID,
        tenantId: String,
    ): String =
        Jwts
            .builder()
            .subject(serviceId.toString())
            .claim(CLAIM_TENANT_ID, tenantId)
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
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "service-link"
    }
}
