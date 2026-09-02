package com.jiku.messaging.internal

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.util.UUID

/**
 * Routes outbound platform email through Resend first, falling back to Brevo
 * once Resend's daily cap is reached, so delivery keeps working past Resend's
 * free-tier ceiling without paying either provider (JIKU-62). Selected with
 * `jiku.mail.transport=routing`. Once both providers' daily caps — and the
 * separate, lower global cap — are exhausted, throws [EmailQuotaExceededException]
 * so [NotificationService] queues the send instead of failing it; the next
 * day's counters (or [NotificationQueueSweepJob]'s retry once they roll over)
 * pick it back up. Tenant BYO credentials (JIKU-44) bypass this entirely — a
 * tenant's own Resend account has its own separate limits, unrelated to the
 * platform's shared quota.
 */
@Component
@ConditionalOnProperty(name = ["jiku.mail.transport"], havingValue = "routing")
class RoutingEmailSender internal constructor(
    private val resend: EmailSender,
    private val brevo: EmailSender,
    private val quota: EmailQuotaCounterRepository,
    private val properties: EmailRoutingProperties,
) : EmailSender {
    @Autowired
    constructor(
        builder: RestClient.Builder,
        @Value("\${jiku.mail.resend.api-key:}") resendApiKey: String,
        @Value("\${jiku.mail.resend.base-url:https://api.resend.com}") resendBaseUrl: String,
        @Value("\${jiku.mail.brevo.api-key:}") brevoApiKey: String,
        @Value("\${jiku.mail.brevo.base-url:https://api.brevo.com/v3}") brevoBaseUrl: String,
        quota: EmailQuotaCounterRepository,
        properties: EmailRoutingProperties,
    ) : this(
        ResendEmailSender(ResendEmailSender.buildClient(builder.clone(), resendApiKey, resendBaseUrl)),
        BrevoEmailSender(BrevoEmailSender.buildClient(builder.clone(), brevoApiKey, brevoBaseUrl)),
        quota,
        properties,
    )

    override fun send(
        from: String,
        message: EmailMessage,
    ) {
        val today = LocalDate.now()
        if (reserve(EmailQuotaCounter.PROVIDER_RESEND, today, properties.resendDailyCap) &&
            reserve(EmailQuotaCounter.PROVIDER_GLOBAL, today, properties.globalDailyCap)
        ) {
            resend.send(from, message)
            return
        }
        if (reserve(EmailQuotaCounter.PROVIDER_BREVO, today, properties.brevoDailyCap) &&
            reserve(EmailQuotaCounter.PROVIDER_GLOBAL, today, properties.globalDailyCap)
        ) {
            brevo.send(from, message)
            return
        }
        throw EmailQuotaExceededException(
            "Resend (${properties.resendDailyCap}/day), Brevo (${properties.brevoDailyCap}/day) or the " +
                "global cap (${properties.globalDailyCap}/day) is exhausted for $today",
        )
    }

    private fun reserve(
        provider: String,
        day: LocalDate,
        cap: Int,
    ): Boolean = quota.tryReserve(UUID.randomUUID(), provider, day, cap) > 0
}
