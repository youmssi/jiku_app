package com.jiku.messaging.internal

import com.jiku.shared.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A single normalized feedback event from a provider webhook. */
data class FeedbackEvent(
    val recipient: String,
    val type: String,
)

/** Platform-wide reputation over the rolling window. */
data class ReputationSnapshot(
    val sends: Long,
    val bounces: Long,
    val complaints: Long,
    val bounceRate: Double,
    val complaintRate: Double,
    val bounceBreach: Boolean,
    val complaintBreach: Boolean,
)

/** A tenant's recent deliverability, surfaced on their dashboard. */
data class DeliverabilitySummary(
    val sent: Long,
    val bounced: Long,
    val bounceRate: Double,
    val warn: Boolean,
)

/**
 * Tracks email bounces/complaints and the platform's sending reputation. One
 * tenant's poorly-cleaned list can degrade deliverability for everyone on the
 * shared infrastructure, so feedback and rates are computed across tenants;
 * per-tenant figures are derived for dashboard flags and import warnings.
 */
@Service
class SenderReputationService(
    private val feedback: EmailFeedbackRepository,
    private val notificationLogs: NotificationLogRepository,
    private val properties: EmailReputationProperties,
) {
    private val log = LoggerFactory.getLogger(SenderReputationService::class.java)

    @Transactional
    fun recordFeedback(events: List<FeedbackEvent>) {
        for (event in events) {
            val type = normalize(event.type) ?: continue
            val recipient = event.recipient.trim().lowercase()
            if (recipient.isEmpty()) continue
            val tenantId = notificationLogs.findTenantOfLatestSend(recipient)
            feedback.save(EmailFeedback(recipient = recipient, feedbackType = type, tenantId = tenantId, referenceId = null))
            // Mirror into the attributed tenant's audit log, so the bounce is
            // visible per-send alongside the original delivery (JIKU-28).
            if (tenantId != null) {
                TenantContext.withTenant(tenantId) {
                    notificationLogs.save(
                        NotificationLog(
                            referenceId = null,
                            channel = EMAIL_CHANNEL,
                            recipient = recipient,
                            status = type,
                            attempt = 0,
                            error = null,
                        ),
                    )
                }
            }
        }
    }

    @Transactional(readOnly = true)
    fun isUndeliverable(email: String): Boolean {
        val recipient = email.trim().lowercase()
        return feedback.countByRecipientAndFeedbackType(recipient, EmailFeedback.TYPE_HARD_BOUNCE) >=
            properties.undeliverableHardBounces
    }

    @Transactional(readOnly = true)
    fun platformReputation(): ReputationSnapshot {
        val since = window()
        val sends = notificationLogs.countSentSinceGlobal(since)
        val bounces = feedback.countByTypesSince(EmailFeedback.BOUNCE_TYPES, since)
        val complaints = feedback.countByTypesSince(setOf(EmailFeedback.TYPE_COMPLAINT), since)
        val bounceRate = rate(bounces, sends)
        val complaintRate = rate(complaints, sends)
        val meaningful = sends >= properties.minSampleSize
        return ReputationSnapshot(
            sends = sends,
            bounces = bounces,
            complaints = complaints,
            bounceRate = bounceRate,
            complaintRate = complaintRate,
            bounceBreach = meaningful && bounceRate > properties.bounceRateThreshold,
            complaintBreach = meaningful && complaintRate > properties.complaintRateThreshold,
        )
    }

    @Transactional(readOnly = true)
    fun currentTenantDeliverability(): DeliverabilitySummary {
        val since = window()
        val sent = notificationLogs.countByStatusAndCreatedAtAfter(NotificationLog.STATUS_SENT, since)
        val tenantId = TenantContext.get()
        val bounced =
            if (tenantId != null) {
                feedback.countByTenantAndTypesSince(tenantId, EmailFeedback.BOUNCE_TYPES, since)
            } else {
                0L
            }
        val bounceRate = rate(bounced, sent)
        val warn = sent >= properties.minSampleSize && bounceRate > properties.bounceRateThreshold
        return DeliverabilitySummary(sent = sent, bounced = bounced, bounceRate = bounceRate, warn = warn)
    }

    fun verifyWebhookSecret(secret: String?): Boolean = secret != null && secret == properties.webhookSecret

    private fun window(): Instant = Instant.now().minus(properties.windowHours, ChronoUnit.HOURS)

    private fun rate(
        numerator: Long,
        denominator: Long,
    ): Double = if (denominator == 0L) 0.0 else numerator.toDouble() / denominator.toDouble()

    private fun normalize(type: String): String? =
        when (type.trim().uppercase()) {
            "HARD_BOUNCE", "HARDBOUNCE", "HARD", "BOUNCE" -> EmailFeedback.TYPE_HARD_BOUNCE
            "SOFT_BOUNCE", "SOFTBOUNCE", "SOFT", "DEFERRED" -> EmailFeedback.TYPE_SOFT_BOUNCE
            "COMPLAINT", "SPAM", "SPAMREPORT", "SPAM_COMPLAINT" -> EmailFeedback.TYPE_COMPLAINT
            else -> {
                log.warn("Ignoring unknown email feedback type: {}", type)
                null
            }
        }


    private companion object {
        const val EMAIL_CHANNEL = "EMAIL"
    }
}
