package com.jiku.invitation.internal

import com.jiku.shared.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Retries invitations withheld by a delivery-capacity guardrail
 * ([InvitationStatus.QUEUED]) once capacity has likely freed up — the WhatsApp
 * 24h conversation window (JIKU-61) and the email provider daily quota (JIKU-62)
 * both queue this way, channel-agnostic. [InvitationDispatchWorker.process] is
 * idempotent for a non-SENT invitation, so re-running it is exactly what a
 * fresh dispatch would do — no separate retry path needed. Runs platform-wide
 * (own schedule, not per-tenant) since the capacity it is waiting on is itself
 * platform- or tenant-pool-wide, not tied to any one request.
 */
@Component
class NotificationQueueSweepJob(
    private val invitations: InvitationRepository,
    private val worker: InvitationDispatchWorker,
) {
    private val log = LoggerFactory.getLogger(NotificationQueueSweepJob::class.java)

    @Scheduled(cron = "\${invitation.queue-sweep-cron:0 */15 * * * *}")
    fun sweep() {
        val queued = invitations.findGlobalQueued()
        if (queued.isEmpty()) {
            return
        }
        log.info("Retrying {} invitation(s) queued by a delivery-capacity guardrail", queued.size)
        for (ref in queued) {
            val previous = TenantContext.get()
            TenantContext.set(ref.tenantId)
            try {
                worker.process(ref.id)
            } finally {
                if (previous != null) TenantContext.set(previous) else TenantContext.clear()
            }
        }
    }
}
