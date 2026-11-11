package com.jiku.messaging.internal

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.LocalDate
import java.util.UUID

/**
 * Routes outbound platform email through a chain of providers, each within its
 * own daily cap and all under a global cap (JIKU-62, JIKU-275). The chain is
 * `jiku.mail.routing.order` (useSend, Resend and Brevo in the order given; ADR 110
 * launches with `usesend,resend,brevo`), so the free tiers add up before anything
 * is paid. Selected with `jiku.mail.transport=routing`; every provider named in
 * the chain needs its API key, checked at startup.
 *
 * Once every cap is reached, useSend's paid plan takes the rest when
 * `jiku.mail.routing.usesend-overflow` is on; otherwise this throws
 * [EmailQuotaExceededException] so [NotificationService] queues the send and the
 * next day's counters (or [NotificationQueueSweepJob]'s retry) pick it back up.
 * Tenant BYO credentials (JIKU-44) bypass this entirely.
 */
@Component
@ConditionalOnProperty(name = ["jiku.mail.transport"], havingValue = "routing")
class RoutingEmailSender internal constructor(
    private val chain: List<Provider>,
    private val overflow: EmailSender?,
    private val quota: EmailQuotaCounterRepository,
    private val properties: EmailRoutingProperties,
) : EmailSender {
    /** One link of the chain: the counter it reserves against and its daily cap. */
    internal class Provider(
        val counter: String,
        val sender: EmailSender,
        val dailyCap: Int,
    )

    @Autowired
    constructor(
        builder: RestClient.Builder,
        @Value("\${jiku.mail.usesend.api-key:}") usesendApiKey: String,
        @Value("\${jiku.mail.usesend.base-url:https://app.usesend.com/api}") usesendBaseUrl: String,
        @Value("\${jiku.mail.resend.api-key:}") resendApiKey: String,
        @Value("\${jiku.mail.resend.base-url:https://api.resend.com}") resendBaseUrl: String,
        @Value("\${jiku.mail.brevo.api-key:}") brevoApiKey: String,
        @Value("\${jiku.mail.brevo.base-url:https://api.brevo.com/v3}") brevoBaseUrl: String,
        quota: EmailQuotaCounterRepository,
        properties: EmailRoutingProperties,
    ) : this(
        buildChain(properties) { name ->
            when (name) {
                USESEND -> UseSendEmailSender(UseSendEmailSender.buildClient(builder.clone(), usesendApiKey, usesendBaseUrl))
                RESEND -> ResendEmailSender(ResendEmailSender.buildClient(builder.clone(), resendApiKey, resendBaseUrl))
                BREVO -> BrevoEmailSender(BrevoEmailSender.buildClient(builder.clone(), brevoApiKey, brevoBaseUrl))
                else -> error("jiku.mail.routing.order names an unknown provider: $name")
            }
        },
        if (properties.usesendOverflow) {
            UseSendEmailSender(UseSendEmailSender.buildClient(builder.clone(), usesendApiKey, usesendBaseUrl))
        } else {
            null
        },
        quota,
        properties,
    )

    override fun send(
        from: String,
        message: EmailMessage,
    ) {
        val today = LocalDate.now()
        for (provider in chain) {
            if (reserve(provider.counter, today, provider.dailyCap) &&
                reserve(EmailQuotaCounter.PROVIDER_GLOBAL, today, properties.globalDailyCap)
            ) {
                provider.sender.send(from, message)
                return
            }
        }
        if (overflow != null) {
            overflow.send(from, message)
            return
        }
        throw EmailQuotaExceededException(
            chain.joinToString(prefix = "Daily caps exhausted for $today: ") { "${it.counter} (${it.dailyCap}/day)" } +
                ", global (${properties.globalDailyCap}/day)",
        )
    }

    private fun reserve(
        provider: String,
        day: LocalDate,
        cap: Int,
    ): Boolean = quota.tryReserve(UUID.randomUUID(), provider, day, cap) > 0

    internal companion object {
        const val USESEND = "usesend"
        const val RESEND = "resend"
        const val BREVO = "brevo"

        fun buildChain(
            properties: EmailRoutingProperties,
            senderFor: (String) -> EmailSender,
        ): List<Provider> =
            properties.order
                .map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }
                .distinct()
                .map { name ->
                    when (name) {
                        USESEND -> Provider(EmailQuotaCounter.PROVIDER_USESEND, senderFor(name), properties.usesendDailyCap)
                        RESEND -> Provider(EmailQuotaCounter.PROVIDER_RESEND, senderFor(name), properties.resendDailyCap)
                        BREVO -> Provider(EmailQuotaCounter.PROVIDER_BREVO, senderFor(name), properties.brevoDailyCap)
                        else -> error("jiku.mail.routing.order names an unknown provider: $name")
                    }
                }
    }
}
