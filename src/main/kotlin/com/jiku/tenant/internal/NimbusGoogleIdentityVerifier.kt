package com.jiku.tenant.internal

import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException

/**
 * Verifies Google ID tokens against Google's published JWKS (JIKU-51): RS256
 * signature, either Google issuer form, the configured client id as audience,
 * and expiry. The decoder is built lazily so the application boots (and every
 * test runs) without a network dependency when Google sign-in is unused.
 */
@Component
class NimbusGoogleIdentityVerifier(
    private val properties: GoogleAuthProperties,
) : GoogleIdentityVerifier {
    private val decoder by lazy {
        NimbusJwtDecoder
            .withJwkSetUri(properties.jwksUri)
            .build()
            .also { it.setJwtValidator(googleValidator()) }
    }

    override fun isConfigured(): Boolean = properties.clientId.isNotBlank()

    override fun verify(idToken: String): GoogleIdentity {
        val jwt =
            try {
                decoder.decode(idToken)
            } catch (ex: JwtException) {
                throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token", ex)
            }
        val email = jwt.getClaimAsString("email").orEmpty()
        if (email.isBlank()) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token")
        }
        return GoogleIdentity(
            email = email,
            emailVerified = jwt.getClaimAsBoolean("email_verified") == true,
        )
    }

    private fun googleValidator(): OAuth2TokenValidator<Jwt> =
        DelegatingOAuth2TokenValidator(
            JwtTimestampValidator(),
            OAuth2TokenValidator { jwt ->
                val issuerOk = jwt.claims["iss"]?.toString() in GOOGLE_ISSUERS
                val audienceOk = properties.clientId in jwt.audience.orEmpty()
                if (issuerOk && audienceOk) {
                    OAuth2TokenValidatorResult.success()
                } else {
                    OAuth2TokenValidatorResult.failure(OAuth2Error("invalid_token", "Wrong issuer or audience", null))
                }
            },
        )

    private companion object {
        // Google documents both forms; which one appears varies by token path.
        val GOOGLE_ISSUERS = setOf("https://accounts.google.com", "accounts.google.com")
    }
}
