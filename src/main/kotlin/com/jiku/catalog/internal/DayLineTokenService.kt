package com.jiku.catalog.internal

import com.jiku.shared.JwtProperties
import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import java.util.UUID
import javax.crypto.SecretKey
import org.springframework.stereotype.Service as SpringService

/**
 * Émet et vérifie le jeton signé du lien du personnel vers la console d'un
 * service (JIKU-88), même mécanique que le lien validateurs et le lien de service
 * client : le jeton porte l'identité de la ligne [ServiceStaff] (sujet), le
 * service et le tenant. Un type propre empêche qu'un lien client ou un lien
 * d'invitation soit utilisé comme lien de comptoir.
 */
@SpringService
class DayLineTokenService(
    properties: JwtProperties,
) {
    private val key: SecretKey = Keys.hmacShaKeyFor(properties.secret.toByteArray())

    fun issue(
        staffId: UUID,
        serviceId: UUID,
        tenantId: String,
    ): String =
        Jwts
            .builder()
            .subject(staffId.toString())
            .claim(CLAIM_SERVICE_ID, serviceId.toString())
            .claim(CLAIM_TENANT_ID, tenantId)
            .claim(CLAIM_TYPE, TOKEN_TYPE)
            .signWith(key)
            .compact()

    fun parse(token: String): Claims {
        val claims =
            Jwts
                .parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .payload
        require(claims[CLAIM_TYPE] == TOKEN_TYPE) { "Not a day-line token" }
        return claims
    }

    companion object {
        const val CLAIM_SERVICE_ID = "serviceId"
        const val CLAIM_TENANT_ID = "tenantId"
        const val CLAIM_TYPE = "type"
        const val TOKEN_TYPE = "dayline"
    }
}
