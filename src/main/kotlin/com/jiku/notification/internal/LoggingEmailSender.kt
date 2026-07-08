package com.jiku.notification.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Default [EmailSender] used until a real provider is configured. It records the
 * send and succeeds, so the end-to-end invitation flow works without a provider.
 * Adding a real `EmailSender` bean replaces it automatically.
 */
class LoggingEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(LoggingEmailSender::class.java)

    override fun send(
        from: String,
        message: EmailMessage,
    ) {
        log.info(
            "Email queued (no provider configured): from={} to={} subject=\"{}\"",
            from,
            message.to,
            message.subject,
        )
    }
}

@Configuration
class EmailSenderConfig {
    @Bean
    @ConditionalOnMissingBean(EmailSender::class)
    fun loggingEmailSender(): EmailSender = LoggingEmailSender()
}
