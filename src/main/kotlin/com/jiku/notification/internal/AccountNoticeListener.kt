package com.jiku.notification.internal

import com.jiku.shared.AccountNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Account-lifecycle emails (JIKU-49): password reset and email verification.
 * Operational one-off mails like the manual-payment notices, sent through the
 * platform sender directly — there is no per-guest lifecycle to orchestrate.
 */
@Component
class AccountNoticeListener(
    private val emailSender: EmailSender,
    private val emailProperties: NotificationEmailProperties,
    private val templateRenderer: EmailTemplateRenderer,
) {
    private val log = LoggerFactory.getLogger(AccountNoticeListener::class.java)

    @EventListener
    fun onAccountNotice(notice: AccountNotice) {
        val (subject, body) =
            when (notice.kind) {
                AccountNotice.KIND_PASSWORD_RESET ->
                    "Reset your Jikū password" to templateRenderer.renderPasswordReset(notice.actionUrl)
                AccountNotice.KIND_EMAIL_VERIFICATION ->
                    "Verify your email address" to templateRenderer.renderVerifyEmail(notice.actionUrl)
                else -> {
                    log.warn("Ignoring account notice of unknown kind: {}", notice.kind)
                    return
                }
            }
        try {
            emailSender.send(
                emailProperties.from,
                EmailMessage(to = notice.email, toName = notice.email, subject = subject, htmlBody = body),
            )
        } catch (ex: Exception) {
            // Never let a mail failure surface to the caller — the endpoints must
            // stay mute about delivery, and the user can simply request again.
            log.error("Failed to send {} email to {}", notice.kind, notice.email, ex)
        }
    }
}
