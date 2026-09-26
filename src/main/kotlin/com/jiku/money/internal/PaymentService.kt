package com.jiku.money.internal

import com.jiku.shared.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Orchestrates online payments (JIKU-33): an event's usage tier, and since
 * JIKU-164 every other purchase — plan months, Organizer Pack months and extra
 * guests, the own WhatsApp number add-on. Initiation records
 * a PENDING payment and asks the provider to start the flow; a tier is only ever
 * unlocked when the provider confirms the payment server-to-server via the
 * signature-verified callback — never on a client-side "success" — and granted
 * through [PaymentFulfillment], the same step an admin confirmation uses. A failed or
 * timed-out payment leaves the allowance untouched. Callback handling is idempotent.
 *
 * The provider comes from [PaymentProviderSelector]: the active one starts a
 * payment, and a callback only settles a payment started by the same provider.
 */
@Service
class PaymentService(
    private val payments: PaymentRepository,
    private val fulfillment: PaymentFulfillment,
    private val providers: PaymentProviderSelector,
    private val platformSettings: PlatformBillingSettingsService,
    private val eventPricing: EventPricing,
    private val subscriptionService: SubscriptionService,
    private val organizerPack: OrganizerPackService,
    private val ownWhatsAppNumber: OwnWhatsAppNumberService,
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
        val quote = eventPricing.upgradeQuote(eventId, tier)
        return start(
            Payment(
                eventId = eventId,
                tier = tier.name,
                amountMinor = quote.amountMinor,
                currency = quote.currency,
                interactive = quote.interactive,
                provider = providers.active.name,
            ),
            "Unlock ${tier.name} tier",
        )
    }

    /** Pays [months] of the services plan [planName] online (JIKU-164). */
    @Transactional
    fun checkoutSubscription(
        planName: String,
        months: Int,
    ): PaymentInitiationResult {
        val plan =
            platformSettings.planByName(planName)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown subscription plan: $planName")
        val quote = subscriptionService.quote(plan, months)
        return start(
            tenantPayment(Payment.KIND_SUBSCRIPTION, plan.name, quote.amountMinor, quote.currency, months, null),
            "${plan.name} plan for $months months",
        )
    }

    /** Pays [months] of Organizer Pack online, with the guests still owed (JIKU-164). */
    @Transactional
    fun checkoutPack(months: Int): PaymentInitiationResult {
        val quote = organizerPack.quote(months)
        return start(
            tenantPayment(Payment.KIND_PACK, Payment.PACK_TIER, quote.amountMinor, quote.currency, months, quote.owedGuests),
            "Organizer Pack for $months months",
        )
    }

    /** Pays [blocks] blocks of extra pack guests online (JIKU-164). */
    @Transactional
    fun checkoutPackExtra(blocks: Int): PaymentInitiationResult {
        val quote = organizerPack.quoteExtra(blocks)
        return start(
            tenantPayment(Payment.KIND_PACK_EXTRA, Payment.PACK_TIER, quote.amountMinor, quote.currency, null, quote.guests),
            "Organizer Pack ${quote.guests} extra guests",
        )
    }

    /** Pays [months] of the own WhatsApp number add-on online (JIKU-164). */
    @Transactional
    fun checkoutOwnWhatsAppNumber(months: Int): PaymentInitiationResult {
        val quote = ownWhatsAppNumber.quote(months)
        return start(
            tenantPayment(Payment.KIND_WHATSAPP_NUMBER, Payment.OWN_NUMBER_TIER, quote.amountMinor, quote.currency, months, 0),
            "Own WhatsApp number for $months months",
        )
    }

    /** Where one of the current organization's payments stands, for the page the payer returns to (JIKU-164). */
    @Transactional(readOnly = true)
    fun status(paymentId: UUID): PaymentStatusView {
        val payment =
            payments.findById(paymentId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Payment not found")
        return PaymentStatusView(
            paymentId = paymentId,
            kind = payment.kind,
            tier = payment.tier,
            eventId = payment.eventId,
            months = payment.subscriptionMonths,
            guests = payment.guests,
            amountMinor = payment.amountMinor,
            currency = payment.currency,
            status = payment.status.name,
            createdAt = payment.createdAt,
            updatedAt = payment.updatedAt,
        )
    }

    private fun tenantPayment(
        kind: String,
        tier: String,
        amountMinor: Long,
        currency: String,
        months: Int?,
        guests: Long?,
    ): Payment =
        Payment(
            eventId = null,
            tier = tier,
            amountMinor = amountMinor,
            currency = currency,
            provider = providers.active.name,
            kind = kind,
            subscriptionMonths = months,
            guests = guests,
        )

    /**
     * Records [payment] as PENDING and asks the active provider to start it. A
     * provider that refuses or cannot be reached rolls the record back, so a
     * failed start leaves no open payment behind.
     */
    private fun start(
        payment: Payment,
        description: String,
    ): PaymentInitiationResult {
        val tenantId = requireNotNull(TenantContext.get()) { "Payment initiation requires an authenticated tenant" }
        val saved = payments.save(payment)
        val paymentId = requireNotNull(saved.id)
        val initiation =
            try {
                providers.active.initiate(
                    PaymentInitiationRequest(
                        paymentId = paymentId,
                        amountMinor = saved.amountMinor,
                        currency = saved.currency,
                        description = description,
                        reference = "$tenantId:$paymentId",
                    ),
                )
            } catch (ex: PaymentProviderException) {
                log.warn("Payment provider {} could not start payment {}: {}", providers.active.name, paymentId, ex.message)
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "The payment provider is unavailable; try again in a moment", ex)
            }
        saved.providerReference = initiation.providerReference
        saved.updatedAt = Instant.now()
        payments.save(saved)

        return PaymentInitiationResult(
            paymentId = paymentId,
            status = saved.status.name,
            amountMinor = saved.amountMinor,
            currency = saved.currency,
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
        fulfillment.fulfill(payment)
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

/** Where a payment stands, for the page the payer lands on after the provider's (JIKU-164). */
data class PaymentStatusView(
    val paymentId: UUID,
    /** TIER, SUBSCRIPTION, PACK, PACK_EXTRA or WHATSAPP_NUMBER. */
    val kind: String,
    val tier: String,
    val eventId: UUID?,
    val months: Int?,
    val guests: Long?,
    val amountMinor: Long,
    val currency: String,
    /** PENDING until the provider confirms, then SUCCEEDED or FAILED. */
    val status: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class PaymentInitiationResult(
    val paymentId: UUID,
    val status: String,
    val amountMinor: Long,
    val currency: String,
    val instruction: PaymentInstruction,
)
