package com.jiku.messaging.internal

import com.jiku.shared.AccountNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Account-lifecycle emails (JIKU-49): password reset and email verification.
 * Content only — the delivery mechanics live in [OperationalMailer].
 */
@Component
class AccountNoticeListener(
    private val mailer: OperationalMailer,
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
        mailer.sendOperationalHtml("account", notice.email, notice.email, subject, body)
    }
}
