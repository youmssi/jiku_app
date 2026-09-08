package com.jiku.shared.observability

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provider-agnostic capture point for unhandled errors. Application code and the
 * global error handler report through this interface; the concrete destination
 * (an error-tracking SaaS such as Sentry, or something self-hosted) is a wiring
 * decision, not a code change. A real adapter is added later as an [ErrorTracker]
 * bean, which replaces the default automatically.
 */
interface ErrorTracker {
    /**
     * Records [throwable] with diagnostic [context] (request id, tenant id, path…)
     * — enough to investigate without reproducing locally. Must never throw:
     * capturing an error must not itself break the request.
     */
    fun capture(
        throwable: Throwable,
        context: Map<String, String>,
    )
}

/**
 * Default tracker used until a provider is wired in: it logs the error (with its
 * context as structured fields) at ERROR level, so unhandled errors are at least
 * visible in the centralized logs. Swaps out for a real adapter via
 * [ConditionalOnMissingBean].
 */
class LoggingErrorTracker : ErrorTracker {
    private val log = LoggerFactory.getLogger(LoggingErrorTracker::class.java)

    override fun capture(
        throwable: Throwable,
        context: Map<String, String>,
    ) {
        try {
            log.error("Unhandled error captured [{}]", context, throwable)
        } catch (ex: Exception) {
            // Reporting must not mask the original failure.
            log.warn("Failed to capture an error", ex)
        }
    }
}

@Configuration
@EnableConfigurationProperties(ErrorTrackingProperties::class)
class ErrorTrackingConfig {
    private val log = LoggerFactory.getLogger(ErrorTrackingConfig::class.java)

    @Bean
    fun personalDataScrubber(): PersonalDataScrubber = PersonalDataScrubber()

    @Bean
    fun errorPayloadScrubber(scrubber: PersonalDataScrubber): ErrorPayloadScrubber = ErrorPayloadScrubber(scrubber)

    /**
     * The tracker follows the DSN: a syntactically usable Sentry URL wires the
     * Sentry adapter, anything else (blank, or a leftover placeholder such as a
     * "DSN Sentry" string) falls back to logging. A misconfigured environment
     * must never prevent the service from starting, and unhandled errors remain
     * visible in the centralized logs either way.
     */
    @Bean
    fun errorTracker(
        properties: ErrorTrackingProperties,
        errorPayloadScrubber: ErrorPayloadScrubber,
    ): ErrorTracker =
        if (isUsableErrorDsn(properties.dsn)) {
            SentryErrorTracker(properties, errorPayloadScrubber)
        } else {
            if (properties.dsn.isNotBlank()) {
                log.warn("Skipping Sentry: the configured DSN is not a usable http(s) URL ({}). Falling back to logging.", properties.dsn)
            }
            LoggingErrorTracker()
        }
}

/** Un DSN exploitable est une URL http(s) bien formée — rien d'autre ne doit activer Sentry. */
internal fun isUsableErrorDsn(dsn: String): Boolean =
    dsn.isNotBlank() &&
        runCatching { java.net.URI(dsn) }
            .map { it.scheme == "http" || it.scheme == "https" }
            .getOrDefault(false)
