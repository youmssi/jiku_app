package com.jiku.invitation.internal

import com.jiku.shared.TenantContext
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Retries invitations withheld by a delivery-capacity guardrail
 * ([InvitationStatus.QUEUED]) once capacity has likely freed up — the WhatsApp
 * 24h conversation window (JIKU-61) and the email provider daily quota (JIKU-62)
 * both queue this way, channel-agnostic. [InvitationDispatchWorker.process] is
 * idempotent for a non-SENT invitation, so re-running it is exactly what a
 * fresh dispatch would do — no separate retry path needed. Runs platform-wide
 * (own schedule, not per-tenant) since the capacity it is waiting on is itself
 * platform- or tenant-pool-wide, not tied to any one request.
 *
 * It also hands over again the invitations still PENDING long after they
 * were handed to the sender (JIKU-215): sending runs in the background, so a
 * restart in the middle of a batch would otherwise leave them unsent.
 */
@Component
class NotificationQueueSweepJob(
    private val invitations: InvitationRepository,
    private val worker: InvitationDispatchWorker,
    @Value("\${invitation.stale-pending-minutes:30}") private val stalePendingMinutes: Long,
) {
    private val log = LoggerFactory.getLogger(NotificationQueueSweepJob::class.java)

    @Scheduled(cron = "\${invitation.queue-sweep-cron:0 */15 * * * *}")
    @SchedulerLock(name = "NotificationQueueSweepJob.sweep")
    fun sweep() {
        val stale = invitations.findGlobalStalePending(Instant.now().minus(stalePendingMinutes, ChronoUnit.MINUTES))
        if (stale.isNotEmpty()) {
            log.warn("Handing over again {} invitation(s) left PENDING, most likely by a restart", stale.size)
            stale.forEach { ref -> TenantContext.withTenant(ref.tenantId) { worker.process(ref.id) } }
        }
        val queued = invitations.findGlobalQueued()
        if (queued.isEmpty()) {
            return
        }
        log.info("Retrying {} invitation(s) queued by a delivery-capacity guardrail", queued.size)
        for (ref in queued) {
            TenantContext.withTenant(ref.tenantId) {
                worker.process(ref.id)
            }
        }
    }
}
