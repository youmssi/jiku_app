package com.jiku.money.internal

import com.jiku.money.AdminPaymentView
import com.jiku.money.ManualPaymentInstructions
import com.jiku.money.PayeeDetails
import com.jiku.shared.ManualPaymentNotice
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * The manual ("concierge") payment flow (JIKU-41), the interim path while no
 * automated payment provider is integrated: the organizer requests a tier, pays
 * the displayed payee by Mobile Money or bank transfer quoting a generated
 * reference, and a platform admin confirms receipt — which unlocks the tier
 * through the same [TierUnlockService] a provider callback uses. Money always
 * moves before activation; the platform never collects any payment credential.
 */
@Service
class ManualPaymentService(
    private val payments: PaymentRepository,
    private val tierUnlockService: TierUnlockService,
    private val billingProperties: BillingProperties,
    private val manualProperties: ManualPaymentProperties,
    private val tenantModuleApi: TenantModuleApi,
    private val usageService: UsageService,
    private val eventPublisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val random = SecureRandom()

    /**
     * Records the activation request and returns the payment instructions.
     * Idempotent per event+tier: re-requesting while a request is open returns the
     * existing one (with its original reference) instead of stacking duplicates.
     */
    @Transactional
    fun request(
        eventId: UUID,
        tierName: String,
    ): ManualPaymentInstructions {
        val tier =
            billingProperties.tiers.firstOrNull { it.name.equals(tierName, ignoreCase = true) }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown tier: $tierName")
        val tenantId = requireNotNull(TenantContext.get()) { "A manual payment request requires an authenticated tenant" }

        val existing =
            payments.findFirstByEventIdAndTierAndProviderAndStatusOrderByCreatedAtDesc(
                eventId,
                tier.name,
                PROVIDER_MANUAL,
                PaymentStatus.PENDING,
            )
        if (existing != null) {
            return instructionsFor(existing)
        }

        // Nets off any deposit/balance already paid toward this event outside this
        // flow (JIKU-57 — a booking that grew past its estimated tier), so an
        // organizer never pays twice for the same guests.
        val discountedAmountMinor = usageService.applyPrepaymentDiscount(eventId, tier.priceMinor)
        val payment =
            payments.save(
                Payment(
                    eventId = eventId,
                    tier = tier.name,
                    amountMinor = discountedAmountMinor,
                    currency = billingProperties.currency,
                    provider = PROVIDER_MANUAL,
                ),
            )
        payment.providerReference = generateReference()
        payment.updatedAt = Instant.now()
        payments.save(payment)

        publishNotice(payment, tenantId, ManualPaymentNotice.KIND_REQUESTED, note = null)
        return instructionsFor(payment)
    }

    /** The organizer's open (or latest) manual payment for an event, if any. */
    @Transactional(readOnly = true)
    fun currentInstructions(eventId: UUID): ManualPaymentInstructions? {
        val pending =
            payments
                .findByEventIdOrderByCreatedAtDesc(eventId)
                .firstOrNull { it.provider == PROVIDER_MANUAL && it.status == PaymentStatus.PENDING }
        return pending?.let { instructionsFor(it) }
    }

    /**
     * Cross-tenant payments list for the back-office. Reads are intentionally
     * unscoped (native query); see [PaymentRepository.adminList].
     */
    fun adminList(
        status: String?,
        provider: String?,
        tenantId: UUID?,
        page: Int,
        size: Int,
    ): List<AdminPaymentView> {
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (normalizedStatus != null && runCatching { PaymentStatus.valueOf(normalizedStatus) }.isFailure) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status")
        }
        val effectiveSize = size.coerceIn(1, MAX_PAGE_SIZE)
        return payments
            .adminList(
                status = normalizedStatus,
                provider = provider?.trim()?.takeIf { it.isNotBlank() },
                tenantId = tenantId?.toString(),
                limit = effectiveSize,
                offset = page.coerceAtLeast(0) * effectiveSize,
            ).map { it.toAdminView() }
    }

    /**
     * Admin confirms the transfer arrived: the payment succeeds and the tier
     * unlocks — atomically, under the payment's own tenant. Idempotence: a
     * non-PENDING payment is refused rather than silently re-processed.
     */
    fun confirm(paymentId: UUID): AdminPaymentView = resolve(paymentId, succeeded = true, note = null)

    /** Admin rejects the request (no matching transfer, mistaken request, …). */
    fun reject(
        paymentId: UUID,
        reason: String,
    ): AdminPaymentView = resolve(paymentId, succeeded = false, note = reason)

    private fun resolve(
        paymentId: UUID,
        succeeded: Boolean,
        note: String?,
    ): AdminPaymentView {
        val tenantId =
            payments.findTenantIdById(paymentId)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found")

        // Admin requests carry no tenant; bind the payment's tenant BEFORE the
        // transaction so the tenant filter resolves to the right tenant when the
        // Hibernate session opens (same pattern as the provider callback).
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            val view =
                transactions.execute {
                    val payment =
                        payments.findById(paymentId).orElse(null)
                            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found")
                    if (payment.provider != PROVIDER_MANUAL) {
                        throw ResponseStatusException(HttpStatus.CONFLICT, "Only manual payments can be resolved by an admin")
                    }
                    if (payment.status != PaymentStatus.PENDING) {
                        throw ResponseStatusException(HttpStatus.CONFLICT, "This payment is already ${payment.status.name.lowercase()}")
                    }
                    payment.status = if (succeeded) PaymentStatus.SUCCEEDED else PaymentStatus.FAILED
                    payment.updatedAt = Instant.now()
                    payments.save(payment)
                    if (succeeded) {
                        tierUnlockService.unlock(payment.eventId, payment.tier)
                    }
                    payment.toAdminView()
                }
            val kind = if (succeeded) ManualPaymentNotice.KIND_CONFIRMED else ManualPaymentNotice.KIND_REJECTED
            publishNoticeFromView(requireNotNull(view), tenantId, kind, note)
            return requireNotNull(view)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun instructionsFor(payment: Payment): ManualPaymentInstructions =
        ManualPaymentInstructions(
            paymentId = requireNotNull(payment.id),
            reference = requireNotNull(payment.providerReference),
            tier = payment.tier,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            status = payment.status.name,
            payee =
                PayeeDetails(
                    payeeName = manualProperties.payeeName.takeIf { it.isNotBlank() },
                    mobileMoneyNumber = manualProperties.mobileMoneyNumber.takeIf { it.isNotBlank() },
                    mobileMoneyOperator = manualProperties.mobileMoneyOperator.takeIf { it.isNotBlank() },
                    bankDetails = manualProperties.bankDetails.takeIf { it.isNotBlank() },
                ),
        )

    private fun publishNotice(
        payment: Payment,
        tenantId: String,
        kind: String,
        note: String?,
    ) {
        publishNoticeFromView(payment.toAdminView(), tenantId, kind, note)
    }

    private fun publishNoticeFromView(
        view: AdminPaymentView,
        tenantId: String,
        kind: String,
        note: String?,
    ) {
        val tenant = runCatching { tenantModuleApi.findTenant(UUID.fromString(tenantId)) }.getOrNull()
        eventPublisher.publishEvent(
            ManualPaymentNotice(
                kind = kind,
                paymentId = view.id,
                tenantId = tenantId,
                eventId = view.eventId,
                tier = view.tier,
                amountMinor = view.amountMinor,
                currency = view.currency,
                reference = view.reference,
                organizerName = tenant?.displayName ?: tenant?.name ?: "Organizer",
                organizerEmail = tenant?.contactEmail ?: "",
                note = note,
            ),
        )
    }

    /**
     * Short, human-friendly transfer reference (e.g. JK-7HTM-Q2WD). The alphabet
     * omits 0/O/1/I/L so a reference read over the phone or typed into a Mobile
     * Money memo survives transcription.
     */
    private fun generateReference(): String {
        val group = { (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("") }
        return "JK-${group()}-${group()}"
    }

    private fun Payment.toAdminView(): AdminPaymentView =
        AdminPaymentView(
            id = requireNotNull(id),
            tenantId = tenantId ?: "",
            eventId = eventId,
            tier = tier,
            amountMinor = amountMinor,
            currency = currency,
            provider = provider,
            reference = providerReference ?: "",
            status = status.name,
            createdAt = createdAt,
        )

    companion object {
        const val PROVIDER_MANUAL = "manual"
        private const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        private const val MAX_PAGE_SIZE = 100
    }
}
