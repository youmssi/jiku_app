package com.jiku.shared

import com.jiku.shared.observability.ErrorPayloadScrubber
import com.jiku.shared.observability.MdcKeys
import com.jiku.shared.observability.PersonalDataScrubber
import io.sentry.Hint
import io.sentry.SentryEvent
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * JIKU-70: nothing personal reaches the error-tracking provider. Guest emails and
 * phone numbers routinely appear in exception messages, and forwarding them to an
 * external processor would undo the erasure and retention guarantees the product
 * makes to guests — who never agreed to appear there and cannot be asked.
 *
 * These are pure unit tests: no Spring context, and no `Sentry.init`, so nothing
 * here can make an outbound call.
 */
class ErrorPayloadScrubbingTest {
    private val payloadScrubber = ErrorPayloadScrubber(PersonalDataScrubber())
    private val scrubber = PersonalDataScrubber()

    @Test
    fun `an email address is redacted`() {
        assertThat(scrubber.scrub("Failed to send to amadou.diallo@example.com after 3 attempts"))
            .isEqualTo("Failed to send to [redacted-email] after 3 attempts")
    }

    @Test
    fun `a phone number is redacted in international and local forms`() {
        assertThat(scrubber.scrub("WhatsApp delivery failed for +224621234567"))
            .isEqualTo("WhatsApp delivery failed for [redacted-phone]")
        assertThat(scrubber.scrub("number 622 12 34 57 unreachable"))
            .isEqualTo("number [redacted-phone] unreachable")
    }

    @Test
    fun `correlation identifiers survive scrubbing`() {
        val requestId = "550e8400-e29b-41d4-a716-446655440000"
        assertThat(scrubber.scrub("request $requestId failed")).contains(requestId)
    }

    @Test
    fun `the diagnostic context is redacted except the correlation identifiers`() {
        val requestId = "550e8400-e29b-41d4-a716-446655440000"
        val tenantId = "6ba7b810-9dad-11d1-80b4-00c04fd430c8"

        val scrubbed =
            payloadScrubber.scrubContext(
                mapOf(
                    MdcKeys.REQUEST_ID to requestId,
                    MdcKeys.TENANT_ID to tenantId,
                    "path" to "/api/v1/guests/awa.camara@example.com",
                    "method" to "GET",
                ),
            )

        assertThat(scrubbed[MdcKeys.REQUEST_ID]).isEqualTo(requestId)
        assertThat(scrubbed[MdcKeys.TENANT_ID]).isEqualTo(tenantId)
        assertThat(scrubbed["method"]).isEqualTo("GET")
        assertThat(scrubbed["path"]).isEqualTo("/api/v1/guests/[redacted-email]")
    }

    @Test
    fun `the assembled event is redacted before it is sent`() {
        val event =
            SentryEvent().apply {
                message =
                    Message().apply {
                        formatted = "Import failed for fatou.barry@example.com"
                        message = "Import failed for fatou.barry@example.com"
                    }
                exceptions =
                    listOf(
                        SentryException().apply {
                            value = "Duplicate guest +224621234567 in row 12"
                        },
                    )
            }

        val sent = payloadScrubber.execute(event, Hint())

        assertThat(sent.message?.formatted).isEqualTo("Import failed for [redacted-email]")
        assertThat(sent.message?.message).isEqualTo("Import failed for [redacted-email]")
        assertThat(sent.exceptions?.single()?.value).isEqualTo("Duplicate guest [redacted-phone] in row 12")
    }
}
