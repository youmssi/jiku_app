package com.jiku.shared.observability

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Error-tracking configuration. [dsn] is the switch: blank (the default) leaves the
 * logging tracker in place, so a fresh clone and the whole test suite run with no
 * provider account and no outbound calls.
 *
 * [release] must match the value the frontend reports for the same deployment,
 * otherwise a backend event and its frontend counterpart cannot be lined up.
 */
@ConfigurationProperties(prefix = "error-tracking")
data class ErrorTrackingProperties(
    val dsn: String = "",
    val environment: String = "local",
    val release: String = "",
)
