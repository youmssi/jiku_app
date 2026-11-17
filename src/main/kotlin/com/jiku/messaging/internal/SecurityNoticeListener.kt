package com.jiku.messaging.internal

import com.jiku.shared.SecurityNotice
import com.jiku.shared.async.Executors
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/** Emails every change to how an account signs in (JIKU-317), so its holder notices one they did not make. */
@Component
class SecurityNoticeListener(
    private val mailer: OperationalMailer,
    private val catalog: MessageCatalog,
) {
    private val log = LoggerFactory.getLogger(SecurityNoticeListener::class.java)

    @Async(Executors.URGENT)
    @TransactionalEventListener(fallbackExecution = true)
    fun onSecurityNotice(notice: SecurityNotice) {
        val values =
            mapOf(
                "at" to catalog.formatOperationalInstant(notice.language, notice.at),
                "device" to (notice.device ?: catalog.text(notice.language, "security.unknown-device", emptyMap())),
                "link" to notice.actionUrl.orEmpty(),
            )
        val copy = catalog.noticeCopy(notice.language, "security", notice.kind, values)
        if (copy == null) {
            log.warn("Ignoring security notice of unknown kind: {}", notice.kind)
            return
        }
        mailer.sendNotice("security", notice.email, notice.email, notice.language, copy)
    }
}
