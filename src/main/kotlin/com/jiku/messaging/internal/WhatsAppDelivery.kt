package com.jiku.messaging.internal

import com.jiku.shared.InvitationDeliveryResult
import com.jiku.shared.OpsAlert
import com.jiku.shared.TenantContext
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A WhatsApp message Meta accepted (JIKU-211), followed until Meta reports what became of it. */
@Entity
@Table(name = "whatsapp_message")
class WhatsAppSentMessage(
    @Id
    @Column(name = "wamid")
    val wamid: String,
    @Column(name = "tenant_id")
    val tenantId: String?,
    @Column(name = "reference_id")
    val referenceId: UUID?,
    @Column(name = "recipient", nullable = false)
    val recipient: String,
    @Column(name = "own_number", nullable = false)
    val ownNumber: Boolean,
    @Column(name = "invitation", nullable = false)
    val invitation: Boolean,
    @Column(name = "sms_fallback")
    var smsFallback: String?,
) {
    @Column(name = "status", nullable = false)
    var status: String = STATUS_SENT

    @Column(name = "error_code")
    var errorCode: Int? = null

    @Column(name = "sent_at", nullable = false)
    val sentAt: Instant = Instant.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()

    companion object {
        const val STATUS_SENT = "SENT"
        const val STATUS_DELIVERED = "DELIVERED"
        const val STATUS_READ = "READ"
        const val STATUS_FAILED = "FAILED"
    }
}

interface WhatsAppSentMessageRepository : JpaRepository<WhatsAppSentMessage, String> {
    fun findFirstByRecipientOrderBySentAtDesc(recipient: String): WhatsAppSentMessage?

    @Query(
        "SELECT COUNT(m) FROM WhatsAppSentMessage m WHERE m.tenantId = :tenantId AND m.ownNumber = false AND m.sentAt >= :since",
    )
    fun countPlatformSince(
        @Param("tenantId") tenantId: String,
        @Param("since") since: Instant,
    ): Long

    @Query(
        "SELECT COUNT(m) FROM WhatsAppSentMessage m WHERE m.tenantId = :tenantId AND m.ownNumber = false " +
            "AND m.sentAt >= :since AND m.status = 'FAILED'",
    )
    fun countPlatformFailedSince(
        @Param("tenantId") tenantId: String,
        @Param("since") since: Instant,
    ): Long
}

/** One status Meta reported for a message it accepted (`statuses[]` in the webhook). */
data class WhatsAppStatusUpdate(
    val wamid: String,
    val status: String,
    val errorCode: Int? = null,
    val errorTitle: String? = null,
)

/**
 * What Meta reports after it accepted a WhatsApp message (JIKU-211). Meta
 * answers "accepted" at once and only later says whether the message reached
 * the phone. A message Meta could not deliver is recorded as failed for its
 * organization, an invitation shows as failed so the organizer sees it, and a
 * message whose channel allows SMS is sent again by SMS.
 */
@Service
class WhatsAppDeliveryStatusService(
    private val messages: WhatsAppSentMessageRepository,
    private val logs: NotificationLogRepository,
    private val smsSender: SmsSender,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(WhatsAppDeliveryStatusService::class.java)

    @Transactional
    fun record(
        wamid: String,
        referenceId: UUID?,
        recipient: String,
        ownNumber: Boolean,
        invitation: Boolean,
        smsFallback: String?,
    ) {
        messages.save(
            WhatsAppSentMessage(
                wamid = wamid,
                tenantId = TenantContext.get(),
                referenceId = referenceId,
                recipient = whatsAppDigits(recipient),
                ownNumber = ownNumber,
                invitation = invitation,
                smsFallback = smsFallback,
            ),
        )
    }

    @Transactional
    fun onStatus(update: WhatsAppStatusUpdate) {
        val message = messages.findById(update.wamid).orElse(null) ?: return
        val status = update.status.uppercase()
        if (message.status == WhatsAppSentMessage.STATUS_FAILED) return
        if (status != WhatsAppSentMessage.STATUS_FAILED && RANK.getOrDefault(status, 0) <= RANK.getOrDefault(message.status, 0)) return
        message.status = status
        message.updatedAt = Instant.now()
        if (status == WhatsAppSentMessage.STATUS_FAILED) {
            message.errorCode = update.errorCode
            failed(message, "WhatsApp could not deliver it (Meta ${update.errorCode ?: "?"}: ${update.errorTitle ?: "no detail"})")
        }
        messages.save(message)
    }

    private fun failed(
        message: WhatsAppSentMessage,
        reason: String,
    ) {
        val tenantId = message.tenantId ?: return
        TenantContext.withTenant(tenantId) {
            logs.save(
                NotificationLog(message.referenceId, WHATSAPP, "+${message.recipient}", NotificationLog.STATUS_FAILED, 0, reason.take(500)),
            )
            val fallback = message.smsFallback
            if (fallback != null) {
                message.smsFallback = null
                val status =
                    try {
                        smsSender.send(SmsMessage(to = "+${message.recipient}", body = SmsText.toGsm7(fallback)))
                        NotificationLog.STATUS_SENT
                    } catch (ex: RuntimeException) {
                        log.warn("SMS fallback for WhatsApp message {} failed", message.wamid, ex)
                        NotificationLog.STATUS_FAILED
                    }
                logs.save(NotificationLog(message.referenceId, SMS, "+${message.recipient}", status, 1, null))
            }
        }
        val invitationId = message.referenceId?.takeIf { message.invitation } ?: return
        events.publishEvent(InvitationDeliveryResult(invitationId, tenantId, delivered = false, attempts = 1, error = reason.take(500)))
    }

    private companion object {
        const val WHATSAPP = "WHATSAPP"
        const val SMS = "SMS"
        val RANK =
            mapOf(
                WhatsAppSentMessage.STATUS_SENT to 1,
                WhatsAppSentMessage.STATUS_DELIVERED to 2,
                WhatsAppSentMessage.STATUS_READ to 3,
            )
    }
}

/** How an organization's WhatsApp sends through the Jikū number are judged (JIKU-211). */
@ConfigurationProperties(prefix = "jiku.whatsapp.reputation")
data class WhatsAppReputationProperties(
    val windowDays: Long = 7,
    /** Messages an organization must have sent through the Jikū number in the window before it can be paused. */
    val minSample: Long = 50,
    /** Share of its recipients writing STOP above which its WhatsApp is paused; 0 turns the check off. */
    val stopRate: Double = 0.02,
    /** Share of its messages Meta could not deliver above which its WhatsApp is paused; 0 turns the check off. */
    val failureRate: Double = 0.2,
)

/** The organization's WhatsApp through the Jikū number is paused (JIKU-211): no retry, SMS takes over where allowed. */
class TenantWhatsAppPausedException(
    message: String,
) : RuntimeException(message)

/**
 * Pauses the Jikū number for one organization whose guests keep writing STOP
 * or whose messages Meta cannot deliver (JIKU-211). Blocks and reports are what
 * lower the number's quality for everyone; an organization sending from its
 * own number spends its own quality and is not checked. The pause lifts by
 * itself as the window moves on. The answer is kept for a minute per
 * organization (`jiku.messaging.check-cache-ttl`), so a batch does not count
 * again for each message.
 */
@Component
@EnableConfigurationProperties(WhatsAppReputationProperties::class)
class TenantWhatsAppPause(
    private val messages: WhatsAppSentMessageRepository,
    private val optOuts: WhatsAppOptOutRepository,
    private val properties: WhatsAppReputationProperties,
    private val events: ApplicationEventPublisher,
    @Value("\${jiku.messaging.check-cache-ttl:60s}") checkCacheTtl: Duration,
) {
    private val alertedOn = ConcurrentHashMap<String, LocalDate>()
    private val reasons = PerTenantCache<String>(checkCacheTtl)

    fun reason(tenantId: String): String? = reasons.get(tenantId) { currentReason(tenantId) }

    private fun currentReason(tenantId: String): String? {
        val since = Instant.now().minus(properties.windowDays, ChronoUnit.DAYS)
        val sent = messages.countPlatformSince(tenantId, since)
        if (sent < properties.minSample) return null
        val stops = optOuts.countByTenantIdAndOptedOutAtAfter(tenantId, since)
        val failed = messages.countPlatformFailedSince(tenantId, since)
        return when {
            properties.stopRate > 0 && stops.toDouble() / sent > properties.stopRate ->
                "$stops of $sent recipients wrote STOP in ${properties.windowDays} days"
            properties.failureRate > 0 && failed.toDouble() / sent > properties.failureRate ->
                "Meta could not deliver $failed of $sent messages in ${properties.windowDays} days"
            else -> null
        }
    }

    fun assertNotPaused(tenantId: String?) {
        val reason = tenantId?.let(::reason) ?: return
        val today = LocalDate.now(ZoneOffset.UTC)
        if (alertedOn.put(tenantId, today) != today) {
            events.publishEvent(
                OpsAlert(
                    subject = "Jikū WhatsApp paused for organization $tenantId",
                    message =
                        "$reason. Its messages through the Jikū number fail and go by SMS where allowed until the rate " +
                            "falls back. Review its guest list and what it sends.",
                ),
            )
        }
        throw TenantWhatsAppPausedException("WhatsApp paused for this organization: $reason")
    }
}
