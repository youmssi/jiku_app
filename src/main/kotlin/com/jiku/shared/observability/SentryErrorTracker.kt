package com.jiku.shared.observability

import io.sentry.Sentry
import io.sentry.SentryOptions
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory

/**
 * Reports unhandled errors to Sentry, scrubbed of personal data by
 * [ErrorPayloadScrubber] on both the diagnostic context and the assembled event.
 *
 * `sendDefaultPii` is disabled explicitly rather than left to the SDK default, so a
 * future version changing that default cannot quietly start attaching request
 * headers, cookies and client IP addresses.
 */
class SentryErrorTracker(
    properties: ErrorTrackingProperties,
    private val payloadScrubber: ErrorPayloadScrubber,
) : ErrorTracker {
    private val log = LoggerFactory.getLogger(SentryErrorTracker::class.java)

    init {
        Sentry.init { options: SentryOptions ->
            options.dsn = properties.dsn
            options.environment = properties.environment
            options.release = properties.release.ifBlank { null }
            options.isSendDefaultPii = false
            options.beforeSend = payloadScrubber
        }
    }

    override fun capture(
        throwable: Throwable,
        context: Map<String, String>,
    ) {
        try {
            val scrubbed = payloadScrubber.scrubContext(context)
            Sentry.captureException(throwable) { scope ->
                scrubbed.forEach { (key, value) -> scope.setTag(key, value) }
            }
        } catch (ex: Exception) {
            // Reporting must not mask the original failure.
            log.warn("Failed to capture an error", ex)
        }
    }

    @PreDestroy
    fun shutdown() {
        Sentry.close()
    }
}
