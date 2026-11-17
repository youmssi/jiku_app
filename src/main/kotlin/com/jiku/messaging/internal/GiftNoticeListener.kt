package com.jiku.messaging.internal

import com.jiku.shared.GiftNotice
import com.jiku.shared.async.Executors
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Organizer emails for gifts (JIKU-315): offered, applied by the desk, about to
 * lapse, lapsed, taken back, in the organization's language.
 */
@Component
class GiftNoticeListener(
    private val mailer: OperationalMailer,
    private val catalog: MessageCatalog,
) {
    private val log = LoggerFactory.getLogger(GiftNoticeListener::class.java)

    @Async(Executors.URGENT)
    @TransactionalEventListener(fallbackExecution = true)
    fun onGiftNotice(notice: GiftNotice) {
        if (notice.organizerEmail.isBlank()) return
        val language = catalog.language(notice.tenantId)
        val values =
            mapOf(
                "gift" to giftText(language, notice),
                "until" to catalog.formatOperationalInstant(language, notice.claimBy),
                "message" to notice.message?.let { catalog.text(language, "gift.message", mapOf("message" to it)) }.orEmpty(),
            )
        val copy = catalog.noticeCopy(language, "gift", notice.kind, values)
        if (copy == null) {
            log.warn("Ignoring gift notice of unknown kind: {}", notice.kind)
            return
        }
        mailer.sendNotice("gift", notice.organizerEmail, notice.organizerName, language, copy)
    }

    private fun giftText(
        language: String,
        notice: GiftNotice,
    ): String =
        catalog.text(
            language,
            "gift.what.${notice.giftKind}",
            mapOf(
                "guests" to notice.guests?.toString().orEmpty(),
                "days" to notice.days?.toString().orEmpty(),
                "plan" to notice.plan.orEmpty(),
                "months" to notice.months?.toString().orEmpty(),
            ),
        )
}
