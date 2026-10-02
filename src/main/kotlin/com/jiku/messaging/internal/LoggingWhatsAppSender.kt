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
        log.info(
            "WhatsApp queued (transport=log, not delivered): to={} buttons={} image={}",
            message.to,
            message.buttons.map { it.id },
            message.imageUrl,
        )
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
        health: WhatsAppHealthService,
    ): WhatsAppSender =
        MetaCloudWhatsAppSender.build(
            builder = builder,
            accessToken = properties.meta.accessToken,
            phoneNumberId = properties.meta.phoneNumberId,
            baseUrl = properties.meta.baseUrl,
            templateName = properties.meta.templateName.ifBlank { null },
            templateLanguage = properties.meta.templateLanguage,
            buttonsTemplateName = properties.meta.buttonsTemplateName.ifBlank { null },
            imageTemplateName = properties.meta.imageTemplateName.ifBlank { null },
            wabaId = properties.meta.businessAccountId.ifBlank { null },
            gate = health,
            templatePrefix = properties.meta.templatePrefix.takeIf { properties.meta.dedicatedTemplates },
        )

    /**
     * The sender of the cards number (JIKU-185): session messages only, since
     * it only ever answers a person who just wrote. Logs like the platform
     * sender when the transport is `log` or the number is not configured.
     */
    @Bean
    fun cardsWhatsAppSender(
        builder: RestClient.Builder,
        properties: WhatsAppProperties,
    ): CardsWhatsAppSender =
        CardsWhatsAppSender(
            if (properties.transport == "meta" && properties.meta.cardsPhoneNumberId.isNotBlank()) {
                MetaCloudWhatsAppSender.build(
                    builder = builder.clone(),
                    accessToken = properties.meta.accessToken,
                    phoneNumberId = properties.meta.cardsPhoneNumberId,
                    baseUrl = properties.meta.baseUrl,
                    templateName = null,
                    templateLanguage = properties.meta.templateLanguage,
                )
            } else {
                LoggingWhatsAppSender()
            },
        )
}

/** Sends from the Jikū number dedicated to cards (JIKU-185), apart from the platform number. */
class CardsWhatsAppSender(
    val sender: WhatsAppSender,
)
