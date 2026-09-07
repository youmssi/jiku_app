package com.jiku.messaging.internal

import com.jiku.shared.SubscriptionNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * E-mails d'abonnement à l'organisateur (JIKU-90) : échéance proche (J-7),
 * entrée en grâce, suspension, réactivation. Courrier opérationnel émis par le
 * sender plateforme, comme les avis d'essai — jamais une carte, jamais un
 * identifiant de paiement.
 */
@Component
class SubscriptionNoticeListener(
    private val emailSender: EmailSender,
    private val emailProperties: NotificationEmailProperties,
    private val templateRenderer: EmailTemplateRenderer,
) {
    private val log = LoggerFactory.getLogger(SubscriptionNoticeListener::class.java)

    @EventListener
    fun onSubscriptionNotice(notice: SubscriptionNotice) {
        val to = notice.organizerEmail.takeIf { it.isNotBlank() }
        if (to == null) {
            log.warn("Subscription {} for tenant {} has no organizer email to notify", notice.kind, notice.tenantId)
            return
        }
        val until = notice.expiresAt?.let { UNTIL_FORMAT.format(it.atZone(ZoneOffset.UTC)) }
        val suspended = notice.suspensionAt?.let { UNTIL_FORMAT.format(it.atZone(ZoneOffset.UTC)) }

        val (subject, heading, body) =
            when (notice.kind) {
                SubscriptionNotice.KIND_EXPIRING ->
                    Triple(
                        "Your ${notice.plan} subscription expires soon",
                        "Your subscription expires soon",
                        "Your ${notice.plan} subscription ends on $until (UTC). Renew from your billing page " +
                            "before then to keep your workspace running without interruption.",
                    )
                SubscriptionNotice.KIND_GRACE_STARTED ->
                    Triple(
                        "Your ${notice.plan} subscription is in grace",
                        "Action needed to avoid suspension",
                        "Your ${notice.plan} subscription expired on $until (UTC). You are in a grace period: " +
                            "renew now, or your workspace will be suspended on $suspended (UTC).",
                    )
                SubscriptionNotice.KIND_EXPIRED ->
                    Triple(
                        "Your ${notice.plan} subscription has ended",
                        "Your workspace has been suspended",
                        "Your ${notice.plan} subscription ended on $until (UTC) and your workspace has been " +
                            "suspended on $suspended (UTC). To reactivate, complete a renewal payment — our team " +
                            "reactivates as soon as it is confirmed.",
                    )
                SubscriptionNotice.KIND_REACTIVATED ->
                    Triple(
                        "Your ${notice.plan} subscription is active",
                        "Your subscription is active again",
                        "Your ${notice.plan} subscription is now active until $until (UTC). Thank you.",
                    )
                else -> {
                    log.warn("Ignoring subscription notice of unknown kind: {}", notice.kind)
                    return
                }
            }
        try {
            emailSender.send(
                emailProperties.from,
                EmailMessage(
                    to = to,
                    toName = notice.organizerName,
                    subject = subject,
                    htmlBody = templateRenderer.renderTrialNotice(notice.organizerName, heading, body),
                ),
            )
        } catch (ex: Exception) {
            log.error("Failed to send subscription email to {}", to, ex)
        }
    }

    private companion object {
        val UNTIL_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm")
    }
}
