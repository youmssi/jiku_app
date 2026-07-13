package com.jiku.tenant.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Account recovery and verification configuration (JIKU-49). [appBaseUrl] is the
 * frontend origin the emailed action links point at — the same SERVER_BASE_URL
 * used for guest invitation and validator links.
 */
@ConfigurationProperties(prefix = "auth.account")
data class AccountProperties(
    val passwordResetTtl: Duration = Duration.ofHours(1),
    val emailVerificationTtl: Duration = Duration.ofDays(2),
    val appBaseUrl: String = "http://localhost:3000",
)
