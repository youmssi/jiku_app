package com.jiku.shared.observability

import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.SentryOptions

/**
 * The single point every error payload passes through on its way to the provider.
 *
 * It covers the two routes personal data can take out of the process, because
 * either one alone leaves a hole:
 *  - [scrubContext] handles the diagnostic map the error handler assembles, and
 *  - [execute] — the SDK's `beforeSend` hook — handles the assembled event, which
 *    catches the message and exception values the SDK derives on its own from the
 *    throwable and each of its causes.
 *
 * Correlation identifiers are exempt: they carry no personal data and are the join
 * key between this event, the application logs and the frontend's report for the
 * same request, so redacting them would destroy the only link a user's problem
 * report has to the recorded event.
 */
class ErrorPayloadScrubber(
    private val scrubber: PersonalDataScrubber,
) : SentryOptions.BeforeSendCallback {
    /** Redacts every diagnostic value except the opaque correlation identifiers. */
    fun scrubContext(context: Map<String, String>): Map<String, String> =
        context.mapValues { (key, value) ->
            if (key in UNSCRUBBED_KEYS) value else scrubber.scrub(value)
        }

    override fun execute(
        event: SentryEvent,
        hint: Hint,
    ): SentryEvent {
        event.message?.let { message ->
            message.formatted = message.formatted?.let(scrubber::scrub)
            message.message = message.message?.let(scrubber::scrub)
        }
        event.exceptions?.forEach { exception ->
            exception.value = exception.value?.let(scrubber::scrub)
        }
        return event
    }

    private companion object {
        val UNSCRUBBED_KEYS = setOf(MdcKeys.REQUEST_ID, MdcKeys.TENANT_ID)
    }
}
