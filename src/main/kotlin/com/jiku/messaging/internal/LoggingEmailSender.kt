package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * No-delivery [EmailSender], selected with `jiku.mail.transport=log` (the
 * default). It records the send and succeeds, so a fresh clone exercises the
 * full invitation flow with zero configuration and no SMTP dependency. Real
 * transports: [SmtpEmailSender] (`smtp`, Mailpit locally) and
 * [ResendEmailSender] (`resend`, production). A [latency] imitates a
 * provider's answer time for load tests (`MAIL_LOG_LATENCY`, JIKU-216).
 */
class LoggingEmailSender(
    private val latency: Duration = Duration.ZERO,
) : EmailSender {
    private val log = LoggerFactory.getLogger(LoggingEmailSender::class.java)

    override fun send(
        from: String,
        message: EmailMessage,
    ) {
        if (!latency.isZero) Thread.sleep(latency)
        log.info(
            "Email queued (transport=log, not delivered): from={} to={} subject=\"{}\" attachments={}",
            from,
            message.to,
            message.subject,
            message.attachments.map { it.filename },
        )
    }
}

@Configuration
class EmailSenderConfig {
    @Bean
    @ConditionalOnProperty(name = ["jiku.mail.transport"], havingValue = "log", matchIfMissing = true)
    fun loggingEmailSender(
        @Value("\${jiku.mail.log-latency:0ms}") latency: Duration,
    ): EmailSender = LoggingEmailSender(latency)
}
