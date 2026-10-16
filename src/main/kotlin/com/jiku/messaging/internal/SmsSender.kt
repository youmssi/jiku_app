package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.time.Duration

/** A rendered SMS ready for delivery by a provider adapter. */
data class SmsMessage(
    val to: String,
    val body: String,
)

/**
 * Provider adapter port for SMS (JIKU-112), for clients without WhatsApp or
 * mobile data. The transport is selected with `jiku.sms.transport`:
 * [LoggingSmsSender] (`log`, default), [NimbaSmsSender] (`nimba`) or
 * [DisabledSmsSender] (`off`, JIKU-261). Adapters throw [SmsDeliveryException]
 * on failure so the orchestration retry engages.
 */
interface SmsSender {
    /** False when the platform sends no SMS at all: callers skip SMS instead of trying it. */
    val enabled: Boolean get() = true

    fun send(message: SmsMessage)
}

/** A provider adapter failed to deliver an SMS. */
class SmsDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

@ConfigurationProperties(prefix = "jiku.sms")
data class SmsProperties(
    val transport: String = "log",
    val nimba: Nimba = Nimba(),
) {
    data class Nimba(
        val serviceId: String = "",
        val secretToken: String = "",
        /** The approved sender name recipients see; case-sensitive at Nimba. */
        val senderName: String = "",
        val baseUrl: String = "https://api.nimbasms.com",
    )
}

/**
 * No-delivery [SmsSender], the default: it records the send and succeeds, so a
 * fresh clone exercises the full flow with no provider account.
 */
class LoggingSmsSender : SmsSender {
    private val log = LoggerFactory.getLogger(LoggingSmsSender::class.java)

    override fun send(message: SmsMessage) {
        log.info("SMS queued (transport=log, not delivered): to={}", message.to)
    }
}

/**
 * SMS turned off for the whole platform (JIKU-261): nothing is sent and nothing
 * is billed. Callers check [enabled] and record the message as not sent; a send
 * that still reaches here fails rather than pretending it went out.
 */
class DisabledSmsSender : SmsSender {
    override val enabled: Boolean = false

    override fun send(message: SmsMessage): Unit = throw SmsDeliveryException("SMS is turned off on this platform")
}

@Configuration
class SmsSenderConfig {
    @Bean
    @ConditionalOnProperty(name = ["jiku.sms.transport"], havingValue = "log", matchIfMissing = true)
    fun loggingSmsSender(): SmsSender = LoggingSmsSender()

    @Bean
    @ConditionalOnProperty(name = ["jiku.sms.transport"], havingValue = "off")
    fun disabledSmsSender(): SmsSender = DisabledSmsSender()

    @Bean
    @ConditionalOnProperty(name = ["jiku.sms.transport"], havingValue = "nimba")
    fun nimbaSmsSender(
        builder: RestClient.Builder,
        properties: SmsProperties,
    ): SmsSender {
        val factory = SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(Duration.ofSeconds(5))
        factory.setReadTimeout(Duration.ofSeconds(15))
        return NimbaSmsSender.build(builder.clone().requestFactory(factory), properties.nimba)
    }
}
