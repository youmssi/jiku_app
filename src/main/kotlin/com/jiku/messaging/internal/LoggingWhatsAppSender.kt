package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient

/**
 * No-delivery [WhatsAppSender], selected with `jiku.whatsapp.transport=log`
 * (the default). It records the send and succeeds, so a fresh clone exercises
 * the full flow with zero configuration. The real adapter is
 * [MetaCloudWhatsAppSender] (`meta`), configured platform-wide here or
 * per tenant in the org settings.
 */
class LoggingWhatsAppSender : WhatsAppSender {
    private val log = LoggerFactory.getLogger(LoggingWhatsAppSender::class.java)

    override fun send(message: WhatsAppMessage) {
        log.info("WhatsApp queued (transport=log, not delivered): to={}", message.to)
    }
}

@Configuration
class WhatsAppSenderConfig {
    @Bean
    @ConditionalOnProperty(name = ["jiku.whatsapp.transport"], havingValue = "log", matchIfMissing = true)
    fun loggingWhatsAppSender(): WhatsAppSender = LoggingWhatsAppSender()

    @Bean
    @ConditionalOnProperty(name = ["jiku.whatsapp.transport"], havingValue = "meta")
    fun metaCloudWhatsAppSender(
        builder: RestClient.Builder,
        properties: WhatsAppProperties,
    ): WhatsAppSender =
        MetaCloudWhatsAppSender.build(
            builder = builder,
            accessToken = properties.meta.accessToken,
            phoneNumberId = properties.meta.phoneNumberId,
            baseUrl = properties.meta.baseUrl,
            templateName = properties.meta.templateName.ifBlank { null },
            templateLanguage = properties.meta.templateLanguage,
        )
}
