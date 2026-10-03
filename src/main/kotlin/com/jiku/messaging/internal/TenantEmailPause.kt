package com.jiku.messaging.internal

import com.jiku.shared.OpsAlert
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** Thrown instead of sending an email while the organization's sending is paused. */
class TenantEmailPausedException(
    message: String,
) : RuntimeException(message)

/**
 * Pauses the platform email of one organization whose list bounces too much
 * (JIKU-207). AWS SES judges the whole shared account, so one badly cleaned
 * list could otherwise put every organization's invitations at risk (ADR 107).
 * Only the platform sender is guarded: an organization sending through its own
 * provider spends its own reputation. The pause lifts by itself once the
 * bounces leave the rolling window. The answer is kept for a minute per
 * organization (`jiku.messaging.check-cache-ttl`), so a batch does not count
 * again for each email.
 */
@Component
class TenantEmailPause(
    private val logs: NotificationLogRepository,
    private val feedback: EmailFeedbackRepository,
    private val properties: EmailReputationProperties,
    private val events: ApplicationEventPublisher,
    @Value("\${jiku.messaging.check-cache-ttl:60s}") checkCacheTtl: Duration,
) {
    private val alertedOn = ConcurrentHashMap<String, LocalDate>()
    private val paused = PerTenantCache<Boolean>(checkCacheTtl)

    fun isPaused(tenantId: String): Boolean = paused.get(tenantId) { bounceRateTooHigh(tenantId) } ?: false

    private fun bounceRateTooHigh(tenantId: String): Boolean {
        if (properties.tenantPauseBounceRate <= 0.0) return false
        val since = Instant.now().minus(properties.windowHours, ChronoUnit.HOURS)
        val sent = logs.countSentByTenantAndChannelSince(tenantId, EMAIL_CHANNEL, since)
        if (sent < properties.tenantPauseMinSample) return false
        val bounced = feedback.countByTenantAndTypesSince(tenantId, setOf(EmailFeedback.TYPE_HARD_BOUNCE), since)
        return bounced.toDouble() / sent.toDouble() > properties.tenantPauseBounceRate
    }

    fun assertNotPaused(tenantId: String?) {
        if (tenantId == null || !isPaused(tenantId)) return
        alertOncePerDay(tenantId)
        throw TenantEmailPausedException(
            "Email paused for this organization: its bounce rate is above " +
                "${percent(properties.tenantPauseBounceRate)} over the last ${properties.windowHours} hours",
        )
    }

    private fun alertOncePerDay(tenantId: String) {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (alertedOn.put(tenantId, today) == today) return
        events.publishEvent(
            OpsAlert(
                subject = "Jikū email paused for organization $tenantId",
                message =
                    "Hard bounces above ${percent(properties.tenantPauseBounceRate)} over the last " +
                        "${properties.windowHours} hours. Its emails are recorded as failed until the rate " +
                        "falls back; ask the organizer to clean the guest list.",
            ),
        )
    }

    private fun percent(rate: Double) = "%.1f %%".format(rate * 100)

    private companion object {
        const val EMAIL_CHANNEL = "EMAIL"
    }
}
