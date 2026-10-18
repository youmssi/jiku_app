package com.jiku.money.internal

import com.jiku.money.ReminderAllowance
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * One read of where the organization stands with everything it pays for
 * (JIKU-254): the services subscription, Pro Organisateur, its own WhatsApp
 * number and the free plan's reminders. [BillingStatusView.attention] names
 * the one thing to act on now, so the app shows a single, clear reminder.
 */
@Service
@Transactional(readOnly = true)
class BillingStatusService(
    private val subscriptions: SubscriptionRepository,
    private val properties: SubscriptionProperties,
    private val platformSettings: PlatformBillingSettingsService,
    private val packs: OrganizerPackService,
    private val ownNumber: OwnWhatsAppNumberService,
    private val reminders: ReminderAllowanceService,
) {
    fun status(now: Instant = Instant.now()): BillingStatusView {
        val subscription = subscriptions.findCurrent().firstOrNull()?.let { summarize(it, now) }
        val pack = packs.view()
        val number = ownNumber.view()
        return BillingStatusView(
            attention = subscription?.attention ?: BillingAttention.NONE,
            subscription = subscription,
            packActive = pack.active,
            packExpiresAt = pack.expiresAt,
            ownWhatsAppNumber = number.allowed,
            ownWhatsAppNumberAddonExpiresAt = number.addonExpiresAt,
            whatsAppReminders = reminders.view(),
        )
    }

    private fun summarize(
        row: Subscription,
        now: Instant,
    ): SubscriptionStatusSummary {
        val overLimit = platformSettings.planByName(row.plan)?.covers(row.resourcesActive) == false
        val graceEnd = row.expiresAt?.plus(properties.grace)
        val attention =
            when {
                row.status == SubscriptionStatus.EXPIRED -> BillingAttention.SERVICES_PAUSED
                row.status == SubscriptionStatus.GRACE -> BillingAttention.GRACE
                overLimit -> BillingAttention.OVER_LIMIT
                row.expiresAt?.let { it.isAfter(now) && it.isBefore(now.plus(properties.noticeLead)) } == true ->
                    BillingAttention.EXPIRING_SOON
                else -> BillingAttention.NONE
            }
        return SubscriptionStatusSummary(
            plan = row.plan,
            status = row.status.name,
            expiresAt = row.expiresAt,
            pausesAt = graceEnd.takeUnless { row.status == SubscriptionStatus.EXPIRED },
            overLimit = overLimit,
            servicesPaused = row.status == SubscriptionStatus.EXPIRED,
            attention = attention,
        )
    }
}

/** The one billing matter to act on now, most urgent first. */
enum class BillingAttention {
    /** The subscription ended: new bookings and walk-ins wait for a renewal. */
    SERVICES_PAUSED,

    /** The paid period is over; services pause at [SubscriptionStatusSummary.pausesAt]. */
    GRACE,

    /** The team has more people than the plan allows. */
    OVER_LIMIT,

    /** The paid period ends within the notice lead. */
    EXPIRING_SOON,
    NONE,
}

/** Everything the organization pays for, in one read (JIKU-254). */
data class BillingStatusView(
    val attention: BillingAttention,
    /** Null when the organization has no services subscription. */
    val subscription: SubscriptionStatusSummary?,
    val packActive: Boolean,
    val packExpiresAt: Instant?,
    val ownWhatsAppNumber: Boolean,
    /** End of the paid add-on, when that is what provides the number. */
    val ownWhatsAppNumberAddonExpiresAt: Instant?,
    /** WhatsApp reminders used this month; null without a monthly cap. */
    val whatsAppReminders: ReminderAllowance?,
)

data class SubscriptionStatusSummary(
    val plan: String,
    /** ACTIVE, GRACE or EXPIRED. */
    val status: String,
    /** End of the paid period; null while a free plan covers the team. */
    val expiresAt: Instant?,
    /** When services pause without a renewal; null once paused or when nothing ends. */
    val pausesAt: Instant?,
    val overLimit: Boolean,
    val servicesPaused: Boolean,
    val attention: BillingAttention,
)
