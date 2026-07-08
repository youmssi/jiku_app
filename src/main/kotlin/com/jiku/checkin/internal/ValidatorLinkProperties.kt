package com.jiku.checkin.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Configuration for validator access links. [appBaseUrl] is the frontend origin
 * used to build the shareable link; [validity] is how long a freshly issued link
 * remains usable before it must be regenerated. Revocation is immediate and
 * independent of [validity].
 */
@ConfigurationProperties(prefix = "checkin.validator-link")
data class ValidatorLinkProperties(
    val appBaseUrl: String = "http://localhost:3000",
    val validity: Duration = Duration.ofDays(30),
)
