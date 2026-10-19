package com.jiku.money.internal

import com.jiku.money.ReminderAllowance
import com.jiku.shared.ReminderAllowanceGate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The WhatsApp reminders a services plan includes each calendar month from
 * Jikū's number: Solo includes [SubscriptionProperties.freePlanWhatsAppReminders]
 * (ADR 105), a paid plan [SubscriptionProperties.paidPlanWhatsAppRemindersPerPerson]
 * for each person who serves clients (fair use, JIKU-268). The count covers
 * reminders actually sent.
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
    override fun canSendWhatsAppReminder(tenantId: String): Boolean = sentThisMonth() < monthlyLimit()

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun recordWhatsAppReminder(tenantId: String) {
        val month = currentMonth()
        val row = usage.findFirstByMonthStart(month) ?: usage.save(ReminderUsage(month))
        row.sent += 1
    }

    /** This month's use for the organizer's billing screen. */
    @Transactional(readOnly = true)
    fun view(): ReminderAllowance = ReminderAllowance(sent = sentThisMonth(), limit = monthlyLimit())

    /** The current plan's monthly cap: a free plan's allowance, or a paid plan's fair use for its people. */
    private fun monthlyLimit(): Int {
        val subscription = subscriptions.findCurrent().firstOrNull()
        val plan = subscription?.plan?.let(platformSettings::planByName)
        if (plan == null || plan.free) return properties.freePlanWhatsAppReminders
        val people = maxOf(subscription.resourcesActive, plan.includedPeople)
        return (people * properties.paidPlanWhatsAppRemindersPerPerson).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun sentThisMonth(): Int = usage.findFirstByMonthStart(currentMonth())?.sent ?: 0

    private fun currentMonth(): LocalDate = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
}
