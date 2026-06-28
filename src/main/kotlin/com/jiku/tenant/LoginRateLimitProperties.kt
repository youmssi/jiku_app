package com.jiku.tenant

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Login throttling configuration. A basic per-IP fixed-window limit at MVP stage;
 * JIKU-9B replaces it with the shared, platform-wide rate-limiting mechanism.
 */
@ConfigurationProperties(prefix = "auth.login-rate-limit")
data class LoginRateLimitProperties(
    val maxAttempts: Int = 5,
    val window: Duration = Duration.ofMinutes(1),
)
