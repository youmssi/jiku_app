package com.jiku.messaging.internal

import com.jiku.shared.TrialNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Organizer emails for the trial lifecycle (JIKU-42): granted, expiring soon,
 * expired, ended early. Content only — the delivery mechanics live in
 * [OperationalMailer].
 */
@Component
class TrialNoticeListener(
    private val mailer: OperationalMailer,
) {
    private val log = LoggerFactory.getLogger(TrialNoticeListener::class.java)

    @EventListener
    fun onTrialNotice(notice: TrialNotice) {
        val until = formatOperationalInstant(notice.expiresAt)
        val (subject, heading, body) =
            when (notice.kind) {
                TrialNotice.KIND_GRANTED ->
                    Triple(
                        "Your ${notice.tier} trial is active",
                        "Your ${notice.tier} trial is active",
                        "Your event now has the ${notice.tier} tier's capacity on trial until $until (UTC). " +
                            "Pay for the tier any time during the trial to keep it.",
                    )
                TrialNotice.KIND_EXPIRING ->
                    Triple(
                        "Your ${notice.tier} trial ends soon",
                        "Your trial ends soon",
                        "Your ${notice.tier} trial ends on $until (UTC). To keep the capacity, request activation " +
                            "from your event's billing page and complete the payment before then.",
                    )
                TrialNotice.KIND_EXPIRED ->
                    Triple(
                        "Your ${notice.tier} trial has ended",
                        "Your trial has ended",
                        "Your ${notice.tier} trial ended on $until (UTC) and your event is back on its previous " +
                            "capacity. You can unlock the tier from your event's billing page whenever you're ready.",
                    )
                TrialNotice.KIND_ENDED ->
                    Triple(
                        "Your ${notice.tier} trial was closed",
                        "Your trial was closed",
                        buildString {
                            append("Your ${notice.tier} trial was closed by our team")
                            notice.note?.takeIf { it.isNotBlank() }?.let { append(": $it") }
                            append(". Contact support if you have any question.")
                        },
                    )
                else -> {
                    log.warn("Ignoring trial notice of unknown kind: {}", notice.kind)
                    return
                }
            }
        mailer.sendOperational("trial", notice.organizerEmail, notice.organizerName, subject, heading, body)
    }
}
