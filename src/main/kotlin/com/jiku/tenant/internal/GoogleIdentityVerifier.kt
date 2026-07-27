package com.jiku.tenant.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * A Google-verified identity extracted from an ID token (JIKU-51). Behind an
 * interface so the flow is testable without Google's signing keys.
 */
data class GoogleIdentity(
    val email: String,
    val emailVerified: Boolean,
    /** The person's display name from the token's `name` claim, when present. */
    val name: String? = null,
)

interface GoogleIdentityVerifier {
    /** Whether a client id is configured; without one the endpoint is disabled. */
    fun isConfigured(): Boolean

    /**
     * Verifies signature, issuer, audience and expiry, and returns the identity.
     * Throws [org.springframework.web.server.ResponseStatusException] on failure.
     */
    fun verify(idToken: String): GoogleIdentity
}

/**
 * The OAuth client id the Google Identity Services button was created with —
 * the ID token's required audience. Blank disables Google sign-in entirely.
 */
@ConfigurationProperties(prefix = "auth.google")
data class GoogleAuthProperties(
    val clientId: String = "",
    val jwksUri: String = "https://www.googleapis.com/oauth2/v3/certs",
)
