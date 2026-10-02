package com.jiku.messaging.internal

import com.jiku.shared.OpsAlert
import com.jiku.shared.TenantContext
import com.jiku.shared.VerificationGate
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/** How much one organization may send through the Jikū WhatsApp number (JIKU-212); 0 turns a limit off. */
@ConfigurationProperties(prefix = "jiku.whatsapp.limits")
data class WhatsAppPlatformLimitProperties(
    /** Messages a day an organization the Jikū team has not verified may send through the Jikū number. */
    val unverifiedDaily: Long = 50,
    /** Messages over 30 days past which an organization must connect its own number. */
    val monthlyBeforeOwnNumber: Long = 500,
)

/** An organization's use of the Jikū WhatsApp number, for its settings page (JIKU-212). */
data class PlatformWhatsAppUsage(
    val sentToday: Long,
    val dailyLimit: Long?,
    val sentLast30Days: Long,
    val monthlyLimit: Long?,
    val verified: Boolean,
)

/**
 * The Jikū WhatsApp number speaks for Jikū, and its quality is shared by every
 * organization (JIKU-212). An organization the team has not verified may send
 * only a few messages a day through it, so a fraudster cannot borrow it for a
 * campaign; past a monthly volume, an organization connects its own number
 * (ADR 105), which carries its own reputation and Meta bills to it. Beyond a
 * limit the message waits in the queue, with the reason, and the team is told
 * once a day. An organization on its own number is not counted.
 */
@Component
@EnableConfigurationProperties(WhatsAppPlatformLimitProperties::class)
class WhatsAppPlatformLimits(
    private val messages: WhatsAppSentMessageRepository,
    private val verification: VerificationGate,
    private val properties: WhatsAppPlatformLimitProperties,
    private val events: ApplicationEventPublisher,
) {
    private val alertedOn = ConcurrentHashMap<String, LocalDate>()

    fun usage(): PlatformWhatsAppUsage? {
        val tenantId = TenantContext.get() ?: return null
        val verified = verification.isVerified()
        return PlatformWhatsAppUsage(
            sentToday = sentSince(tenantId, 1),
            dailyLimit = properties.unverifiedDaily.takeIf { it > 0 && !verified },
            sentLast30Days = sentSince(tenantId, MONTH_DAYS),
            monthlyLimit = properties.monthlyBeforeOwnNumber.takeIf { it > 0 },
            verified = verified,
        )
    }

    fun assertWithinLimits() {
        val tenantId = TenantContext.get() ?: return
        val daily = properties.unverifiedDaily
        if (daily > 0 && !verification.isVerified() && sentSince(tenantId, 1) >= daily) {
            refuse(
                tenantId,
                "daily",
                "Your organization is not verified yet: it can send $daily WhatsApp messages a day from the Jikū number. " +
                    "Verify it in the settings to send more.",
            )
        }
        val monthly = properties.monthlyBeforeOwnNumber
        if (monthly > 0 && sentSince(tenantId, MONTH_DAYS) >= monthly) {
            refuse(
                tenantId,
                "monthly",
                "Your organization sent $monthly WhatsApp messages from the Jikū number in 30 days. " +
                    "Connect your own WhatsApp number in the settings to keep sending.",
            )
        }
    }

    private fun sentSince(
        tenantId: String,
        days: Long,
    ): Long = messages.countPlatformSince(tenantId, Instant.now().minus(days, ChronoUnit.DAYS))

    private fun refuse(
        tenantId: String,
        limit: String,
        reason: String,
    ): Nothing {
        val today = LocalDate.now(ZoneOffset.UTC)
        if (alertedOn.put("$tenantId:$limit", today) != today) {
            events.publishEvent(OpsAlert(subject = "Jikū WhatsApp $limit limit reached by organization $tenantId", message = reason))
        }
        throw WhatsAppQuotaExceededException(reason)
    }

    private companion object {
        const val MONTH_DAYS = 30L
    }
}
