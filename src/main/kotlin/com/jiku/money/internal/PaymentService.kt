package com.jiku.money.internal

import com.jiku.shared.TenantContext
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates Mobile Money payments for a usage tier (JIKU-33). Initiation records
 * a PENDING payment and asks the provider to start the flow; a tier is only ever
 * unlocked when the provider confirms the payment server-to-server via the
 * signature-verified callback — never on a client-side "success". A failed or
 * timed-out payment leaves the allowance untouched. Callback handling is idempotent.
 */
@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val tierUnlockService: TierUnlockService,
    private val provider: PaymentProvider,
    private val billingProperties: BillingProperties,
    transactionManager: PlatformTransactionManager,
) {
    enum class CallbackOutcome { SUCCEEDED, FAILED, ALREADY_PROCESSED, UNKNOWN, INVALID_SIGNATURE }

    private val transactions = TransactionTemplate(transactionManager)

    @Transactional
    fun initiate(
        eventId: UUID,
        tierName: String,
    ): PaymentInitiationResult {
        val tier =
            billingProperties.tiers.firstOrNull { it.name.equals(tierName, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown tier: $tierName")
        val tenantId = requireNotNull(TenantContext.get()) { "Payment initiation requires an authenticated tenant" }

        val payment =
            payments.save(
                Payment(
                    eventId = eventId,
                    tier = tier.name,
                    amountMinor = tier.priceMinor,
                    currency = billingProperties.currency,
                    provider = provider.name,
                ),
            )
        val paymentId = requireNotNull(payment.id)
        val reference = "$tenantId:$paymentId"
        val initiation =
            provider.initiate(
                PaymentInitiationRequest(
                    paymentId = paymentId,
                    amountMinor = tier.priceMinor,
                    currency = billingProperties.currency,
                    description = "Unlock ${tier.name} tier",
                    reference = reference,
                ),
            )
        payment.providerReference = initiation.providerReference
        payment.updatedAt = Instant.now()
        payments.save(payment)

        return PaymentInitiationResult(
            paymentId = paymentId,
            status = payment.status.name,
            amountMinor = tier.priceMinor,
            currency = billingProperties.currency,
            instruction = initiation.instruction,
        )
    }

    fun handleCallback(
        rawBody: String,
        signature: String?,
    ): CallbackOutcome {
        val callback = provider.parseCallback(rawBody, signature) ?: return CallbackOutcome.INVALID_SIGNATURE
        val parts = callback.reference.split(":", limit = 2)
        if (parts.size != 2) return CallbackOutcome.UNKNOWN
        val tenantId = parts[0]
        val paymentId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return CallbackOutcome.UNKNOWN

        // The webhook is unauthenticated, so bind the tenant carried in the
        // (signature-verified) reference BEFORE the transaction opens — the tenant
        // filter is resolved when the Hibernate session starts, so binding it after
        // would scope the reads to the wrong (unresolved) tenant.
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            return transactions.execute { confirm(paymentId, callback.succeeded) }
                ?: CallbackOutcome.UNKNOWN
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun confirm(
        paymentId: UUID,
        succeeded: Boolean,
    ): CallbackOutcome {
        val payment = payments.findById(paymentId).orElse(null) ?: return CallbackOutcome.UNKNOWN
        if (payment.status != PaymentStatus.PENDING) {
            return CallbackOutcome.ALREADY_PROCESSED
        }
        payment.status = if (succeeded) PaymentStatus.SUCCEEDED else PaymentStatus.FAILED
        payment.updatedAt = Instant.now()
        payments.save(payment)
        if (succeeded) {
            tierUnlockService.unlock(requireNotNull(payment.eventId) { "A provider payment always references an event" }, payment.tier)
            return CallbackOutcome.SUCCEEDED
        }
        return CallbackOutcome.FAILED
    }
}

data class PaymentInitiationResult(
    val paymentId: UUID,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val instruction: PaymentInstruction,
)
