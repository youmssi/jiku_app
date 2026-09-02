package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * No-delivery [EmailSender], selected with `jiku.mail.transport=log` (the
 * default). It records the send and succeeds, so a fresh clone exercises the
 * full invitation flow with zero configuration and no SMTP dependency. Real
 * transports: [SmtpEmailSender] (`smtp`, Mailpit locally) and
 * [ResendEmailSender] (`resend`, production).
 */
class LoggingEmailSender : EmailSender {
    private val log = LoggerFactory.getLogger(LoggingEmailSender::class.java)

    override fun send(
        from: String,
        message: EmailMessage,
    ) {
        log.info(
            "Email queued (transport=log, not delivered): from={} to={} subject=\"{}\"",
            from,
            message.to,
            message.subject,
        )
    }
}

@Configuration
class EmailSenderConfig {
    @Bean
    @ConditionalOnProperty(name = ["jiku.mail.transport"], havingValue = "log", matchIfMissing = true)
    fun loggingEmailSender(): EmailSender = LoggingEmailSender()
}
