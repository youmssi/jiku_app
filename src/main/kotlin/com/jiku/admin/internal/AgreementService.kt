package com.jiku.admin.internal

import com.jiku.shared.OpsAlert
import com.jiku.tenant.TenantModuleApi
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Enterprise agreements (JIKU-43). Creation validates the tenant and the period;
 * renewal closes the current row and opens the next one so past periods stay on
 * record; interruption is the deliberate stop — for ENTERPRISE_SAAS it also
 * pulls the platform kill switch on the tenant. Expiry is only a marker plus an
 * ops alert: whether a lapsed enterprise deal gets grace is a human decision.
 */
@Service
class AgreementService(
    private val agreements: AgreementRepository,
    private val tenantModuleApi: TenantModuleApi,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(AgreementService::class.java)

    @Transactional
    fun create(request: CreateAgreementRequest): Agreement {
        val tenantId = requireNotNull(request.tenantId)
        val kind = parseKind(request.kind)
        val periodStart = requireNotNull(request.periodStart)
        val periodEnd = requireNotNull(request.periodEnd)
        if (!periodEnd.isAfter(periodStart)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The period end must be after its start")
        }
        tenantModuleApi.findTenant(tenantId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found")
        if (agreements.existsByTenantIdAndKindAndStatus(tenantId, kind, AgreementStatus.ACTIVE)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This tenant already has an active ${kind.name} agreement")
        }
        return agreements.save(
            Agreement(
                tenantId = tenantId,
                kind = kind,
                periodStart = periodStart,
                periodEnd = periodEnd,
                renewalAt = request.renewalAt ?: periodEnd,
                amountMinor = request.amountMinor,
                currency = request.currency?.takeIf { it.isNotBlank() }?.uppercase(),
                notes = request.notes?.takeIf { it.isNotBlank() },
            ),
        )
    }

    fun list(
        status: String?,
        kind: String?,
        tenantId: UUID?,
        page: Int,
        size: Int,
    ): List<Agreement> {
        val statusFilter =
            status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }?.let {
                runCatching { AgreementStatus.valueOf(it) }.getOrNull()
                    ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status")
            }
        val kindFilter = kind?.trim()?.takeIf { it.isNotBlank() }?.let { parseKind(it) }
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        return agreements.search(statusFilter, kindFilter, tenantId, pageable).content
    }

    /**
     * Closes the current period as RENEWED and opens the next one. The new period
     * starts where the old one ends (an early renewal keeps the original end).
     */
    @Transactional
    fun renew(
        agreementId: UUID,
        request: RenewAgreementRequest,
    ): Agreement {
        val current = loadActive(agreementId)
        val newEnd = requireNotNull(request.periodEnd)
        if (!newEnd.isAfter(current.periodEnd)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The renewed period must end after the current one")
        }
        val next =
            agreements.save(
                Agreement(
                    tenantId = current.tenantId,
                    kind = current.kind,
                    periodStart = current.periodEnd,
                    periodEnd = newEnd,
                    renewalAt = request.renewalAt ?: newEnd,
                    amountMinor = request.amountMinor ?: current.amountMinor,
                    currency = request.currency?.takeIf { it.isNotBlank() }?.uppercase() ?: current.currency,
                    notes = request.notes?.takeIf { it.isNotBlank() } ?: current.notes,
                ),
            )
        current.status = AgreementStatus.RENEWED
        current.renewedBy = next.id
        current.updatedAt = Instant.now()
        agreements.save(current)
        return next
    }

    @Transactional
    fun interrupt(
        agreementId: UUID,
        reason: String,
    ): Agreement {
        val agreement = loadActive(agreementId)
        agreement.status = AgreementStatus.INTERRUPTED
        agreement.interruptedReason = reason
        agreement.updatedAt = Instant.now()
        val saved = agreements.save(agreement)
        // On-premise deployments run outside the platform, so there is nothing to
        // suspend; a SaaS deal's tenant loses access with the agreement.
        if (agreement.kind == AgreementKind.ENTERPRISE_SAAS) {
            tenantModuleApi.setTenantSuspended(agreement.tenantId, true)
        }
        return saved
    }

    /** Marks past-end ACTIVE agreements EXPIRED and alerts the operating team. */
    @Transactional
    fun expireDue(now: Instant = Instant.now()): Int {
        val due = agreements.findByStatusAndPeriodEndBefore(AgreementStatus.ACTIVE, now)
        for (agreement in due) {
            agreement.status = AgreementStatus.EXPIRED
            agreement.updatedAt = Instant.now()
            agreements.save(agreement)
            val tenant = tenantModuleApi.findTenant(agreement.tenantId)
            eventPublisher.publishEvent(
                OpsAlert(
                    subject = "Enterprise agreement expired — ${tenant?.name ?: agreement.tenantId}",
                    message =
                        "The ${agreement.kind.name} agreement for ${tenant?.name ?: "tenant ${agreement.tenantId}"} " +
                            "ended on ${agreement.periodEnd}. Decide on grace, renewal, or suspension from the back-office.",
                ),
            )
            log.info("Agreement {} expired ({} for tenant {})", agreement.id, agreement.kind, agreement.tenantId)
        }
        return due.size
    }

    private fun loadActive(agreementId: UUID): Agreement {
        val agreement =
            agreements.findById(agreementId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Agreement not found")
        if (agreement.status != AgreementStatus.ACTIVE) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This agreement is already ${agreement.status.name.lowercase()}")
        }
        return agreement
    }

    private fun parseKind(value: String): AgreementKind =
        runCatching { AgreementKind.valueOf(value.trim().uppercase()) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown kind: $value")

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
