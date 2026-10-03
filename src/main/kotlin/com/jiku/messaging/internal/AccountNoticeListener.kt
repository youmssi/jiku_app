package com.jiku.messaging.internal

import com.jiku.shared.AccountNotice
import com.jiku.shared.async.Executors
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

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

    @Async(Executors.URGENT)
    @TransactionalEventListener(fallbackExecution = true)
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
