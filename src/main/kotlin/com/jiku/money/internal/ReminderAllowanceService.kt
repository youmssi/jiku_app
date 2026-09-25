package com.jiku.money.internal

import com.jiku.money.ReminderAllowance
import com.jiku.shared.ReminderAllowanceGate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The WhatsApp reminders a free services plan includes each calendar month
 * (ADR 105: Solo includes [SubscriptionProperties.freePlanWhatsAppReminders]).
 * Paid plans have no such cap. The count covers reminders actually sent.
 *
 * The gate runs in its own transaction: its callers (event listeners) may have
 * opened theirs before binding the tenant, and the count must land on the
 * tenant it belongs to.
 */
@Service
class ReminderAllowanceService(
    private val usage: ReminderUsageRepository,
    private val subscriptions: SubscriptionRepository,
    private val platformSettings: PlatformBillingSettingsService,
    private val properties: SubscriptionProperties,
) : ReminderAllowanceGate {
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    override fun canSendWhatsAppReminder(tenantId: String): Boolean {
        val limit = monthlyLimit() ?: return true
        return sentThisMonth() < limit
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordWhatsAppReminder(tenantId: String) {
        val month = currentMonth()
        val row = usage.findFirstByMonthStart(month) ?: usage.save(ReminderUsage(month))
        row.sent += 1
    }

    /** This month's use for the organizer's billing screen; null when the plan has no cap. */
    @Transactional(readOnly = true)
    fun view(): ReminderAllowance? = monthlyLimit()?.let { ReminderAllowance(sent = sentThisMonth(), limit = it) }

    /** The current plan's monthly cap: a free plan's allowance, or null for a paid one. */
    private fun monthlyLimit(): Int? {
        val plan =
            subscriptions
                .findCurrent()
                .firstOrNull()
                ?.plan
                ?.let(platformSettings::planByName)
        return if (plan == null || plan.free) properties.freePlanWhatsAppReminders else null
    }

    private fun sentThisMonth(): Int = usage.findFirstByMonthStart(currentMonth())?.sent ?: 0

    private fun currentMonth(): LocalDate = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
}
