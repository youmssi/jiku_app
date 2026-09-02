package com.jiku.messaging.internal

import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Guards Meta's per-tier 24h conversation-window limit (JIKU-61). Counts actual
 * recorded sends ([WhatsAppMessageCost] rows) rather than an in-memory counter
 * so the guard survives a restart and stays correct across multiple instances.
 *
 * Deliberately not `@Transactional` itself: this is called from inside
 * [NotificationService]'s existing transaction (the caller's), and throwing from
 * a *separately* `@Transactional`-advised method — even one the caller goes on
 * to catch — marks that shared transaction rollback-only regardless, which is
 * not the intent here (a queued send must not roll back the audit log write).
 */
@Component
class WhatsAppConversationCounter(
    private val costs: WhatsAppMessageCostRepository,
    private val properties: WhatsAppProperties,
) {
    fun assertWithinBudget(tenantOverride: Boolean) {
        val since = Instant.now().minus(WINDOW)
        val count =
            if (tenantOverride) {
                costs.countByPoolAndCreatedAtAfter(WhatsAppMessageCost.POOL_TENANT, since)
            } else {
                costs.countPlatformSince(since)
            }
        if (count >= properties.conversationSafetyThreshold) {
            val pool = if (tenantOverride) "tenant" else "platform"
            throw WhatsAppQuotaExceededException(
                "WhatsApp $pool 24h conversation window at $count/${properties.conversationSafetyThreshold} " +
                    "(safety threshold) — queuing until capacity frees up",
            )
        }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofHours(24)
    }
}
