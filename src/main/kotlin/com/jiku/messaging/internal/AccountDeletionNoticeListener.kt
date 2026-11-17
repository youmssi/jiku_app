package com.jiku.messaging.internal

import com.jiku.shared.AccountDeletionNotice
import com.jiku.shared.async.Executors
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/** Emails each step of an account deletion (JIKU-316), in the language it was asked in. */
@Component
class AccountDeletionNoticeListener(
    private val mailer: OperationalMailer,
    private val catalog: MessageCatalog,
) {
    private val log = LoggerFactory.getLogger(AccountDeletionNoticeListener::class.java)

    @Async(Executors.URGENT)
    @TransactionalEventListener(fallbackExecution = true)
    fun onAccountDeletionNotice(notice: AccountDeletionNotice) {
        val organizations =
            if (notice.organizations.isEmpty()) {
                ""
            } else {
                catalog.text(notice.language, "account-deletion.organizations", mapOf("names" to notice.organizations.joinToString(", ")))
            }
        val values =
            mapOf(
                "until" to notice.scheduledFor?.let { catalog.formatOperationalInstant(notice.language, it) }.orEmpty(),
                "organizations" to organizations,
                "names" to notice.organizations.joinToString(", "),
                "link" to notice.actionUrl.orEmpty(),
            )
        val copy = catalog.noticeCopy(notice.language, "account-deletion", notice.kind, values)
        if (copy == null) {
            log.warn("Ignoring account deletion notice of unknown kind: {}", notice.kind)
            return
        }
        mailer.sendNotice("account-deletion", notice.email, notice.email, notice.language, copy)
    }
}
