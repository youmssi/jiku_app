package com.jiku.messaging.internal

import jakarta.mail.MessagingException
import jakarta.mail.internet.InternetAddress
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.MailException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/**
 * SMTP transport, selected with `jiku.mail.transport=smtp`. Local development
 * points it at the Mailpit container from docker-compose (captures mail on
 * localhost:1025 with a web UI on 8025); any real SMTP relay works the same way
 * through the standard `spring.mail.*` properties (MAIL_HOST, MAIL_PORT, …).
 */
@Component
@ConditionalOnProperty(name = ["jiku.mail.transport"], havingValue = "smtp")
class SmtpEmailSender(
    private val mailSender: JavaMailSender,
) : EmailSender {
    override fun send(
        from: String,
        message: EmailMessage,
    ) {
        val mime = mailSender.createMimeMessage()
        try {
            val helper = MimeMessageHelper(mime, false, "UTF-8")
            helper.setFrom(from)
            helper.setTo(InternetAddress(message.to, message.toName.ifBlank { null }, "UTF-8"))
            helper.setSubject(message.subject)
            helper.setText(message.htmlBody, true)
        } catch (e: MessagingException) {
            throw EmailDeliveryException("Failed to assemble email to ${message.to}", e)
        }
        try {
            mailSender.send(mime)
        } catch (e: MailException) {
            throw EmailDeliveryException("SMTP delivery failed for ${message.to}", e)
        }
    }
}
