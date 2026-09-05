package com.jiku.money.internal

import com.jiku.money.AdminPaymentView
import com.jiku.money.AdminTrialView
import com.jiku.money.BillingAllowance
import com.jiku.money.BillingModuleApi
import com.jiku.money.BillingTierOption
import com.jiku.money.BookingAvoirDocument
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Exposes the billing module's metering to other modules (the organizer dashboard
 * for display, the invitation module for the paywall) without any of them reaching
 * into billing's tables, plus the payments desk consumed by the admin module.
 */
@Service
class BillingModuleApiService(
    private val usageService: UsageService,
    private val manualPaymentService: ManualPaymentService,
    private val trialService: TrialService,
    private val tierUnlockService: TierUnlockService,
    private val invoiceService: InvoiceService,
    private val properties: BillingProperties,
) : BillingModuleApi {
    override fun recordPrepayment(
        eventId: UUID,
        amountMinor: Long,
    ) = usageService.recordPrepayment(eventId, amountMinor)

    override fun allowance(eventId: UUID): BillingAllowance = usageService.allowance(eventId)

    override fun canInvite(
        eventId: UUID,
        additionalGuests: Long,
    ): Boolean = usageService.canInvite(eventId, additionalGuests)

    override fun adminListPayments(
        status: String?,
        provider: String?,
        tenantId: UUID?,
        page: Int,
        size: Int,
    ): List<AdminPaymentView> = manualPaymentService.adminList(status, provider, tenantId, page, size)

    override fun adminConfirmManualPayment(paymentId: UUID): AdminPaymentView = manualPaymentService.confirm(paymentId)

    override fun adminRejectManualPayment(
        paymentId: UUID,
        reason: String,
    ): AdminPaymentView = manualPaymentService.reject(paymentId, reason)

    override fun adminListTrials(
        status: String?,
        tenantId: UUID?,
        page: Int,
        size: Int,
    ): List<AdminTrialView> = trialService.adminList(status, tenantId, page, size)

    override fun adminGrantTrial(
        tenantId: UUID,
        eventId: UUID,
        tier: String,
        expiresAt: Instant,
    ): AdminTrialView = trialService.grant(tenantId, eventId, tier, expiresAt)

    override fun adminEndTrial(
        trialId: UUID,
        reason: String,
    ): AdminTrialView = trialService.endEarly(trialId, reason)

    override fun tierForGuestCount(guestCount: Long): String = properties.tierForUsage(guestCount)

    override fun priceForTier(
        tierName: String,
        guestCount: Long,
    ): Long =
        if (tierName == BillingProperties.FREE_TIER) {
            0
        } else {
            properties.tiers.firstOrNull { it.name == tierName }?.priceMinor
                ?: properties.custom.priceGnf(guestCount)
        }

    override fun currency(): String = properties.currency

    override fun tierOptions(): List<BillingTierOption> =
        properties.tiers.map { BillingTierOption(name = it.name, maxGuests = it.maxGuests, priceMinor = it.priceMinor) }

    override fun unlockTier(
        eventId: UUID,
        tierName: String,
    ) = tierUnlockService.unlock(eventId, tierName)

    override fun issueBookingAvoir(
        customerName: String,
        customerCountry: String,
        amountMinor: Long,
        currency: String,
        description: String,
    ): BookingAvoirDocument = invoiceService.issueBookingAvoir(customerName, customerCountry, amountMinor, currency, description)
}
