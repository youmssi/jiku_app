package com.jiku.notification.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Default [WhatsAppSender] used until a BSP is configured. It records the send and
 * succeeds; adding a real `WhatsAppSender` bean replaces it automatically.
 */
class LoggingWhatsAppSender : WhatsAppSender {
    private val log = LoggerFactory.getLogger(LoggingWhatsAppSender::class.java)

    override fun send(message: WhatsAppMessage) {
        log.info("WhatsApp queued (no provider configured): to={}", message.to)
    }
}

@Configuration
class WhatsAppSenderConfig {
    @Bean
    @ConditionalOnMissingBean(WhatsAppSender::class)
    fun loggingWhatsAppSender(): WhatsAppSender = LoggingWhatsAppSender()
}
