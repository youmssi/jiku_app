package com.jiku.money.internal

import com.jiku.shared.TenantContext
import org.slf4j.LoggerFactory
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
 *
 * The provider comes from [PaymentProviderSelector]: the active one starts a
 * payment, and a callback only settles a payment started by the same provider.
 */
@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val tierUnlockService: TierUnlockService,
    private val providers: PaymentProviderSelector,
    private val billingProperties: BillingProperties,
    private val platformSettings: PlatformBillingSettingsService,
    transactionManager: PlatformTransactionManager,
) {
    enum class CallbackOutcome {
        SUCCEEDED,
        FAILED,
        PENDING,
        ALREADY_PROCESSED,
        UNKNOWN,
        UNKNOWN_PROVIDER,
        INVALID_SIGNATURE,
        PROVIDER_UNAVAILABLE,
    }

    private val log = LoggerFactory.getLogger(PaymentService::class.java)
    private val transactions = TransactionTemplate(transactionManager)

    @Transactional
    fun initiate(
        eventId: UUID,
        tierName: String,
    ): PaymentInitiationResult {
        val tier =
            platformSettings.tierByName(tierName)
                ?: throw IllegalArgumentException("Unknown tier: $tierName")
        val tenantId = requireNotNull(TenantContext.get()) { "Payment initiation requires an authenticated tenant" }
        val provider = providers.active

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

    /**
     * Settles a payment from its provider's callback. [providerName] names the
     * adapter that must verify it; `null` means the active one, for providers
     * configured with the historical callback URL that carries no name.
     */
    fun handleCallback(
        providerName: String?,
        rawBody: String,
        signature: String?,
    ): CallbackOutcome {
        val provider =
            (if (providerName == null) providers.active else providers.byName(providerName))
                ?: return CallbackOutcome.UNKNOWN_PROVIDER
        val callback =
            try {
                provider.parseCallback(rawBody, signature)
            } catch (ex: PaymentProviderException) {
                log.warn("Payment provider {} could not verify a callback: {}", provider.name, ex.message)
                return CallbackOutcome.PROVIDER_UNAVAILABLE
            } ?: return CallbackOutcome.INVALID_SIGNATURE
        val parts = callback.reference.split(":", limit = 2)
        if (parts.size != 2) return CallbackOutcome.UNKNOWN
        val tenantId = parts[0]
        val paymentId = runCatching { UUID.fromString(parts[1]) }.getOrNull() ?: return CallbackOutcome.UNKNOWN

        // The webhook is unauthenticated, so bind the tenant carried in the
        // (verified) reference BEFORE the transaction opens — the tenant filter is
        // resolved when the Hibernate session starts, so binding it after would
        // scope the reads to the wrong (unresolved) tenant.
        return TenantContext.withTenant(tenantId) {
            transactions.execute { confirm(paymentId, provider.name, callback) } ?: CallbackOutcome.UNKNOWN
        }
    }

    private fun confirm(
        paymentId: UUID,
        providerName: String,
        callback: PaymentCallback,
    ): CallbackOutcome {
        val payment = payments.findById(paymentId).orElse(null) ?: return CallbackOutcome.UNKNOWN
        // A provider can only vouch for the payments it started itself.
        if (payment.provider != providerName) return CallbackOutcome.UNKNOWN
        if (payment.status != PaymentStatus.PENDING) return CallbackOutcome.ALREADY_PROCESSED
        if (callback.outcome == PaymentOutcome.PENDING) return CallbackOutcome.PENDING

        val succeeded = callback.outcome == PaymentOutcome.SUCCEEDED && paidInFull(payment, callback)
        payment.status = if (succeeded) PaymentStatus.SUCCEEDED else PaymentStatus.FAILED
        payment.updatedAt = Instant.now()
        payments.save(payment)
        if (!succeeded) return CallbackOutcome.FAILED
        tierUnlockService.unlock(requireNotNull(payment.eventId) { "A provider payment always references an event" }, payment.tier)
        return CallbackOutcome.SUCCEEDED
    }

    /** A provider that reports what was paid must report exactly what was asked. */
    private fun paidInFull(
        payment: Payment,
        callback: PaymentCallback,
    ): Boolean {
        val amountMatches = callback.amountMinor == null || callback.amountMinor == payment.amountMinor
        val currencyMatches = callback.currency == null || callback.currency.equals(payment.currency, ignoreCase = true)
        if (!amountMatches || !currencyMatches) {
            log.warn(
                "Payment {} reported as {} {} instead of {} {}; not unlocking",
                payment.id,
                callback.amountMinor,
                callback.currency,
                payment.amountMinor,
                payment.currency,
            )
        }
        return amountMatches && currencyMatches
    }
}

data class PaymentInitiationResult(
    val paymentId: UUID,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val instruction: PaymentInstruction,
)
