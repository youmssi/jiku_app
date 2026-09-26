package com.jiku.messaging.internal

import com.jiku.shared.TrialNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Organizer emails for the trial lifecycle (JIKU-42): granted, expiring soon,
 * expired, ended early — in the organization's language. Content only — the
 * delivery mechanics live in [OperationalMailer].
 */
@Component
class TrialNoticeListener(
    private val mailer: OperationalMailer,
    private val catalog: MessageCatalog,
) {
    private val log = LoggerFactory.getLogger(TrialNoticeListener::class.java)

    @EventListener
    fun onTrialNotice(notice: TrialNotice) {
        val language = catalog.language(notice.tenantId)
        val reason = notice.note?.takeIf { it.isNotBlank() }?.let { catalog.text(language, "trial.reason", mapOf("note" to it)) }
        val values =
            mapOf(
                "tier" to notice.tier,
                "until" to catalog.formatOperationalInstant(language, notice.expiresAt),
                "reason" to reason.orEmpty(),
            )
        val copy = catalog.noticeCopy(language, "trial", notice.kind, values)
        if (copy == null) {
            log.warn("Ignoring trial notice of unknown kind: {}", notice.kind)
            return
        }
        mailer.sendNotice("trial", notice.organizerEmail, notice.organizerName, language, copy)
    }
}
