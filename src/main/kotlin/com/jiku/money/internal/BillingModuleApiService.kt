package com.jiku.money.internal

import com.jiku.money.AdminPaymentView
import com.jiku.money.AdminTrialPage
import com.jiku.money.AdminTrialStats
import com.jiku.money.AdminTrialView
import com.jiku.money.BillingAllowance
import com.jiku.money.BillingModuleApi
import com.jiku.money.BillingTierOption
import com.jiku.money.PlatformBillingSettingsUpdate
import com.jiku.money.PlatformBillingSettingsView
import com.jiku.money.PriceList
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
    private val platformSettings: PlatformBillingSettingsService,
    private val properties: BillingProperties,
) : BillingModuleApi {
    override fun allowance(eventId: UUID): BillingAllowance = usageService.allowance(eventId)

    override fun readAllowance(eventId: UUID): BillingAllowance = usageService.readAllowance(eventId)

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
    ): AdminTrialPage = trialService.adminList(status, tenantId, page, size)

    override fun adminTrialStats(): AdminTrialStats = trialService.adminStats()

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

    override fun currency(): String = PriceList.GNF

    override fun tierOptions(): List<BillingTierOption> =
        platformSettings.tiers().map { BillingTierOption(name = it.name, maxGuests = it.maxGuests, price = it.price) }

    override fun adminBillingSettings(): PlatformBillingSettingsView = platformSettings.view()

    override fun adminUpdateBillingSettings(
        update: PlatformBillingSettingsUpdate,
        updatedBy: String?,
    ): PlatformBillingSettingsView = platformSettings.update(update, updatedBy)
}
