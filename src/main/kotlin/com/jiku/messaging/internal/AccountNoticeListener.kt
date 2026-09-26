package com.jiku.messaging.internal

import com.jiku.shared.AccountNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Account-lifecycle emails (JIKU-49): password reset and email verification, in the language
 * the request was made in. Content only — the delivery mechanics live in [OperationalMailer].
 */
@Component
class AccountNoticeListener(
    private val mailer: OperationalMailer,
    private val templateRenderer: EmailTemplateRenderer,
) {
    private val log = LoggerFactory.getLogger(AccountNoticeListener::class.java)

    @EventListener
    fun onAccountNotice(notice: AccountNotice) {
        val email =
            when (notice.kind) {
                AccountNotice.KIND_PASSWORD_RESET -> templateRenderer.renderPasswordReset(notice.actionUrl, notice.language)
                AccountNotice.KIND_EMAIL_VERIFICATION -> templateRenderer.renderVerifyEmail(notice.actionUrl, notice.language)
                else -> {
                    log.warn("Ignoring account notice of unknown kind: {}", notice.kind)
                    return
                }
            }
        mailer.send("account", notice.email, notice.email, email)
    }
}
