package com.jiku.shared

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * JWT signing and lifetime configuration. [secret] has no default and is required
 * at startup, so a missing value fails fast rather than signing tokens with a
 * guessed key.
 */
@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    val secret: String,
    val accessTokenExpiration: Duration = Duration.ofHours(1),
    val refreshTokenExpiration: Duration = Duration.ofDays(7),
)
