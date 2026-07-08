package com.jiku.shared.observability

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
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
class ErrorTrackingConfig {
    @Bean
    @ConditionalOnMissingBean(ErrorTracker::class)
    fun loggingErrorTracker(): ErrorTracker = LoggingErrorTracker()
}
