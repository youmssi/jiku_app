package com.jiku.billing.internal

import com.jiku.billing.AdminTrialView
import com.jiku.shared.TenantContext
import com.jiku.shared.TrialNotice
import com.jiku.tenant.TenantModuleApi
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Time-boxed trial allowances (JIKU-42). An admin grants an event a paid tier
 * until an expiry instant; while live, the trial raises the event's effective
 * allowance (see UsageService) without ever touching the paid entitlement. The
 * sweep converts a trial whose tier was paid for in the meantime and reverts the
 * rest — so a paid unlock is never rolled back, and an unpaid trial always is.
 */
@Service
class TrialService(
    private val trials: TrialGrantRepository,
    private val payments: PaymentRepository,
    private val billingProperties: BillingProperties,
    private val trialProperties: TrialProperties,
    private val tenantModuleApi: TenantModuleApi,
    private val eventPublisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(TrialService::class.java)
    private val transactions = TransactionTemplate(transactionManager)

    /** The allowance a live trial currently lends the event, if any. */
    fun liveTrialAllowance(eventId: UUID): Long? =
        trials
            .findFirstByEventIdAndStatusOrderByCreatedAtDesc(eventId, TrialStatus.ACTIVE)
            ?.takeIf { it.isLive(Instant.now()) }
            ?.grantedAllowance

    fun grant(
        tenantId: UUID,
        eventId: UUID,
        tierName: String,
        expiresAt: Instant,
    ): AdminTrialView {
        val tier =
            billingProperties.tiers.firstOrNull { it.name.equals(tierName, ignoreCase = true) }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tier: $tierName")
        if (!expiresAt.isAfter(Instant.now())) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The expiry must be in the future")
        }
        return withTenant(tenantId.toString()) {
            val view =
                transactions.execute {
                    if (trials.findFirstByEventIdAndStatusOrderByCreatedAtDesc(eventId, TrialStatus.ACTIVE) != null) {
                        throw ResponseStatusException(HttpStatus.CONFLICT, "This event already has an active trial")
                    }
                    trials
                        .save(
                            TrialGrant(
                                eventId = eventId,
                                tier = tier.name,
                                grantedAllowance = tier.maxGuests,
                                expiresAt = expiresAt,
                            ),
                        ).toView(tenantId.toString())
                }
            publishNotice(requireNotNull(view), TrialNotice.KIND_GRANTED, note = null)
            view
        }
    }

    fun endEarly(
        trialId: UUID,
        reason: String,
    ): AdminTrialView {
        val tenantId =
            trials.findTenantIdById(trialId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Trial not found")
        return withTenant(tenantId) {
            val view =
                transactions.execute {
                    val trial = loadActive(trialId)
                    trial.status = TrialStatus.ENDED
                    trial.endedReason = reason
                    trial.updatedAt = Instant.now()
                    trials.save(trial).toView(tenantId)
                }
            publishNotice(requireNotNull(view), TrialNotice.KIND_ENDED, note = reason)
            view
        }
    }

    fun adminList(
        status: String?,
        tenantId: UUID?,
        page: Int,
        size: Int,
    ): List<AdminTrialView> {
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (normalized != null && runCatching { TrialStatus.valueOf(normalized) }.isFailure) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status")
        }
        val effectiveSize = size.coerceIn(1, MAX_PAGE_SIZE)
        return trials
            .adminList(
                status = normalized,
                tenantId = tenantId?.toString(),
                limit = effectiveSize,
                offset = page.coerceAtLeast(0) * effectiveSize,
            ).map { it.toView(it.tenantId ?: "") }
    }

    /**
     * The expiry sweep: converts each due trial whose tier was paid for during the
     * trial window, expires the rest. One trial per transaction under its own
     * tenant, so a single bad row cannot wedge the sweep.
     */
    fun expireDue(now: Instant = Instant.now()): Int {
        var processed = 0
        for (row in trials.findDueForExpiry(now)) {
            val trialId = UUID.fromString(row[0].toString())
            val tenantId = row[1].toString()
            try {
                withTenant(tenantId) {
                    val view =
                        transactions.execute {
                            val trial = trials.findById(trialId).orElse(null) ?: return@execute null
                            if (trial.status != TrialStatus.ACTIVE) return@execute null
                            val paid = hasAnySucceededPayment(trial.eventId, trial.tier)
                            trial.status = if (paid) TrialStatus.CONVERTED else TrialStatus.EXPIRED
                            trial.updatedAt = Instant.now()
                            trials.save(trial).toView(tenantId)
                        }
                    if (view != null && view.status == TrialStatus.EXPIRED.name) {
                        publishNotice(view, TrialNotice.KIND_EXPIRED, note = null)
                    }
                }
                processed++
            } catch (ex: Exception) {
                log.error("Failed to process expiry of trial {}", trialId, ex)
            }
        }
        return processed
    }

    /** Sends the one near-expiry notice per trial inside the configured lead window. */
    fun notifyExpiring(now: Instant = Instant.now()): Int {
        var notified = 0
        for (row in trials.findDueForExpiryNotice(now, now.plus(trialProperties.noticeLead))) {
            val trialId = UUID.fromString(row[0].toString())
            val tenantId = row[1].toString()
            try {
                withTenant(tenantId) {
                    val view =
                        transactions.execute {
                            val trial = trials.findById(trialId).orElse(null) ?: return@execute null
                            if (trial.status != TrialStatus.ACTIVE || trial.expiryNoticeSent) return@execute null
                            trial.expiryNoticeSent = true
                            trial.updatedAt = Instant.now()
                            trials.save(trial).toView(tenantId)
                        }
                    if (view != null) {
                        publishNotice(view, TrialNotice.KIND_EXPIRING, note = null)
                        notified++
                    }
                }
            } catch (ex: Exception) {
                log.error("Failed to send expiry notice for trial {}", trialId, ex)
            }
        }
        return notified
    }

    /** Any confirmed payment for the event's tier (any provider) converts the trial. */
    private fun hasAnySucceededPayment(
        eventId: UUID,
        tier: String,
    ): Boolean =
        payments
            .findByEventIdOrderByCreatedAtDesc(eventId)
            .any { it.tier == tier && it.status == PaymentStatus.SUCCEEDED }

    private fun loadActive(trialId: UUID): TrialGrant {
        val trial =
            trials.findById(trialId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Trial not found")
        if (trial.status != TrialStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This trial is already ${trial.status.name.lowercase()}")
        }
        return trial
    }

    private fun publishNotice(
        view: AdminTrialView,
        kind: String,
        note: String?,
    ) {
        val tenant = runCatching { tenantModuleApi.findTenant(UUID.fromString(view.tenantId)) }.getOrNull()
        eventPublisher.publishEvent(
            TrialNotice(
                kind = kind,
                trialId = view.id,
                tenantId = view.tenantId,
                eventId = view.eventId,
                tier = view.tier,
                expiresAt = view.expiresAt,
                organizerName = tenant?.displayName ?: tenant?.name ?: "Organizer",
                organizerEmail = tenant?.contactEmail ?: "",
                note = note,
            ),
        )
    }

    private fun <T> withTenant(
        tenantId: String,
        block: () -> T,
    ): T {
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            return block()
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun TrialGrant.toView(tenantId: String): AdminTrialView =
        AdminTrialView(
            id = requireNotNull(id),
            tenantId = tenantId,
            eventId = eventId,
            tier = tier,
            grantedAllowance = grantedAllowance,
            expiresAt = expiresAt,
            status = status.name,
            endedReason = endedReason,
            createdAt = createdAt,
        )

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
