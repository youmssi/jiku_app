package com.jiku.money.internal

import com.jiku.money.MonthOption
import com.jiku.shared.OwnWhatsAppNumberGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.ZoneOffset

/**
 * Sending from the organization's own WhatsApp number (ADR 105). Meta then
 * bills the messages to the organization directly. The Organisation plan and
 * an active Organizer Pack include it; any other plan buys it as a monthly
 * add-on, months counted from the payment and a year charged as ten months.
 */
@Service
class OwnWhatsAppNumberService(
    private val addons: WhatsAppNumberAddonRepository,
    private val subscriptions: SubscriptionRepository,
    private val organizerPack: OrganizerPackService,
    private val billing: BillingProperties,
    private val subscriptionProperties: SubscriptionProperties,
    private val billingCurrency: TenantBillingCurrency,
) : OwnWhatsAppNumberGate {
    private val properties get() = billing.ownWhatsAppNumber

    @Transactional(readOnly = true)
    override fun ownNumberAllowed(): Boolean = source(Instant.now()) != OwnNumberSource.NONE

    @Transactional(readOnly = true)
    fun view(): OwnWhatsAppNumberView {
        val now = Instant.now()
        val currency = billingCurrency.current()
        return OwnWhatsAppNumberView(
            allowed = source(now) != OwnNumberSource.NONE,
            source = source(now),
            addonExpiresAt = activeAddon(now)?.expiresAt,
            includedPlans = properties.includedPlans,
            currency = currency,
            monthlyMinor = properties.monthly.amountMinor(currency),
            months = subscriptionProperties.periods.map { MonthOption(it.months, it.chargedMonths) },
        )
    }

    /** Price of [months] of the add-on in the tenant's billing currency; refused when a plan or the pack already includes it. */
    @Transactional(readOnly = true)
    fun quote(months: Int): PackQuote {
        val period =
            subscriptionProperties.period(months)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported prepaid period: $months months")
        val source = source(Instant.now())
        if (source == OwnNumberSource.PLAN || source == OwnNumberSource.PACK) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Your current offer already includes your own WhatsApp number")
        }
        val currency = billingCurrency.current()
        return PackQuote(amountMinor = properties.monthly.amountMinor(currency) * period.chargedMonths, currency = currency)
    }

    /** An add-on payment was confirmed: it starts, or is extended from its current end when that is still ahead. */
    @Transactional
    fun confirm(months: Int) {
        if (subscriptionProperties.period(months) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported prepaid period: $months months")
        }
        val now = Instant.now()
        val row = addons.findFirstByOrderByExpiresAtDesc()
        if (row == null) {
            addons.save(WhatsAppNumberAddon(expiresAt = plusMonths(now, months.toLong())))
            return
        }
        row.expiresAt = plusMonths(maxOf(row.expiresAt, now), months.toLong())
        row.updatedAt = now
    }

    private fun source(now: Instant): OwnNumberSource =
        when {
            planIncludesIt() -> OwnNumberSource.PLAN
            organizerPack.isActive() -> OwnNumberSource.PACK
            activeAddon(now) != null -> OwnNumberSource.ADDON
            else -> OwnNumberSource.NONE
        }

    private fun planIncludesIt(): Boolean {
        val subscription = subscriptions.findCurrent().firstOrNull() ?: return false
        return subscription.status != SubscriptionStatus.EXPIRED &&
            properties.includedPlans.any { it.equals(subscription.plan, ignoreCase = true) }
    }

    private fun activeAddon(now: Instant): WhatsAppNumberAddon? =
        addons.findFirstByOrderByExpiresAtDesc()?.takeIf { it.expiresAt.isAfter(now) }

    private fun plusMonths(
        instant: Instant,
        months: Long,
    ): Instant = instant.atZone(ZoneOffset.UTC).plusMonths(months).toInstant()
}

enum class OwnNumberSource { PLAN, PACK, ADDON, NONE }

/** Whether the organization may send from its own WhatsApp number, and the add-on's price in its billing currency. */
data class OwnWhatsAppNumberView(
    val allowed: Boolean,
    /** What includes it: the plan, the Organizer Pack, the paid add-on, or nothing yet. */
    val source: OwnNumberSource,
    val addonExpiresAt: Instant?,
    val includedPlans: List<String>,
    val currency: String,
    val monthlyMinor: Long,
    val months: List<MonthOption>,
)
