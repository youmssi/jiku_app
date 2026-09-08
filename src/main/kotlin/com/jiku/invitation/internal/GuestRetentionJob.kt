package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.RetentionProperties
import com.jiku.shared.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Enforces the data-retention policy (JIKU-37): on a schedule, it finds events past
 * their retention window and anonymizes their guests through the same
 * [GuestErasureService] the self-service erasure uses (no second anonymization
 * path). Each event's own tenant is bound before its guests are touched, so the
 * platform-wide sweep still respects tenant isolation.
 */
@Component
class GuestRetentionJob(
    private val events: EventModuleApi,
    private val guests: GuestRepository,
    private val erasureService: GuestErasureService,
    private val properties: RetentionProperties,
) {
    private val log = LoggerFactory.getLogger(GuestRetentionJob::class.java)

    @Scheduled(cron = "\${compliance.retention.cron:0 30 3 * * *}")
    fun enforceRetention() {
        val anonymized = anonymizePastEvents()
        if (anonymized > 0) {
            log.info("Retention policy anonymized {} guest record(s) past their retention window", anonymized)
        }
    }

    /** Anonymizes every not-yet-erased guest of every event past retention. Returns the count. */
    fun anonymizePastEvents(): Int {
        val cutoff = Instant.now().minus(Duration.ofDays(properties.days))
        var anonymized = 0
        for (candidate in events.eventsPastRetention(cutoff)) {
            TenantContext.withTenant(candidate.tenantId) {
                for (guest in guests.findByEventIdAndPersonalDataErasedFalse(candidate.eventId)) {
                    if (erasureService.eraseGuest(requireNotNull(guest.id), ErasureReason.RETENTION_POLICY)) {
                        anonymized++
                    }
                }
            }
        }
        return anonymized
    }
}
