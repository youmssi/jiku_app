package com.jiku.money.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.money.MonthOption
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The Organizer Pack (ADR 105): a monthly allowance of guests shared by every
 * event of the organization, all delivery modes included, instead of a tier per
 * event. Months run from the first payment, like the services subscription, and
 * a year is charged as ten months. Guests beyond the month's allowance are
 * bought ahead in blocks; an event on its day never stops sending, and the
 * guests it sends past the allowance are owed and paid with the next renewal.
 */
@Service
class OrganizerPackService(
    private val packs: OrganizerPackRepository,
    private val packMonths: OrganizerPackMonthRepository,
    private val billing: BillingProperties,
    private val subscriptionProperties: SubscriptionProperties,
    private val billingCurrency: TenantBillingCurrency,
    private val events: EventModuleApi,
) {
    private val pack get() = billing.pack

    @Transactional(readOnly = true)
    fun view(): PackView {
        val now = Instant.now()
        val currency = billingCurrency.current()
        val row = packs.findFirstByOrderByStartedAtAsc()
        val active = row?.takeIf { it.expiresAt.isAfter(now) }
        val monthStart = active?.let { monthStart(it, now) }
        val month = monthStart?.let { packMonths.findFirstByMonthStart(it) }
        val extra = month?.extraGuests ?: 0
        val used = month?.usedGuests ?: 0
        val owed = row?.owedGuests ?: 0
        return PackView(
            active = active != null,
            startedAt = row?.startedAt,
            expiresAt = row?.expiresAt,
            monthStart = monthStart,
            monthEnd = monthStart?.let { plusMonths(it, 1) },
            includedGuests = pack.includedGuests,
            extraGuests = extra,
            usedGuests = used,
            remainingGuests = if (active == null) 0 else (pack.includedGuests + extra - used).coerceAtLeast(0),
            owedGuests = owed,
            currency = currency,
            monthlyMinor = pack.monthly.amountMinor(currency),
            extraPerGuestMinor = pack.extraPerGuest.amountMinor(currency),
            extraBlockGuests = pack.extraBlockGuests,
            owedMinor = owed * pack.extraPerGuest.amountMinor(currency),
            months = subscriptionProperties.periods.map { MonthOption(it.months, it.chargedMonths) },
        )
    }

    /** Whether the current tenant's pack covers its events right now. */
    @Transactional(readOnly = true)
    fun isActive(): Boolean = activePack(Instant.now()) != null

    /**
     * The most distinct guests [eventId] may invite under the pack, or null
     * without one: what it already committed plus the month's remaining
     * guests, and no limit at all on the event's day unless [unlimitedOnEventDay]
     * is off (to show the month's allowance rather than enforce it).
     */
    @Transactional(readOnly = true)
    fun ceiling(
        eventId: UUID,
        alreadyCommitted: Long,
        unlimitedOnEventDay: Boolean = true,
    ): Long? {
        val now = Instant.now()
        val row = activePack(now) ?: return null
        if (unlimitedOnEventDay && isEventDay(eventId, now)) return Long.MAX_VALUE
        val month = packMonths.findFirstByMonthStart(monthStart(row, now))
        val remaining = pack.includedGuests + (month?.extraGuests ?: 0) - (month?.usedGuests ?: 0)
        return alreadyCommitted + remaining.coerceAtLeast(0)
    }

    /**
     * Counts [newGuestCount] guests just sent against the month; any past its
     * allowance are owed. Returns false when there is no pack to count them.
     */
    @Transactional
    fun recordCommitment(newGuestCount: Long): Boolean {
        val now = Instant.now()
        val row = activePack(now) ?: return false
        val month = currentMonth(row, now)
        val limit = pack.includedGuests + month.extraGuests
        val before = month.usedGuests
        month.usedGuests = before + newGuestCount
        val owed = month.usedGuests - maxOf(before, limit)
        if (owed > 0) {
            row.owedGuests += owed
            row.updatedAt = now
        }
        return true
    }

    /** Price of [months] of pack, plus the guests still owed, in the tenant's billing currency. */
    @Transactional(readOnly = true)
    fun quote(months: Int): PackQuote {
        val period =
            subscriptionProperties.period(months)
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported prepaid period: $months months")
        val currency = billingCurrency.current()
        val owed = packs.findFirstByOrderByStartedAtAsc()?.owedGuests ?: 0
        return PackQuote(
            amountMinor = pack.monthly.amountMinor(currency) * period.chargedMonths + owed * pack.extraPerGuest.amountMinor(currency),
            currency = currency,
            owedGuests = owed,
        )
    }

    /** Price of [blocks] blocks of extra guests for the current month; only with an active pack. */
    @Transactional(readOnly = true)
    fun quoteExtra(blocks: Int): PackQuote {
        if (blocks < 1) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Buy at least one block of guests")
        if (!isActive()) throw ResponseStatusException(HttpStatus.CONFLICT, "Extra guests need an active Organizer Pack")
        val currency = billingCurrency.current()
        val guests = blocks * pack.extraBlockGuests
        return PackQuote(amountMinor = guests * pack.extraPerGuest.amountMinor(currency), currency = currency, guests = guests)
    }

    /**
     * A pack payment was confirmed: the pack starts, or is extended by the
     * months paid from its current end when that is still ahead, and the
     * guests the payment settled are no longer owed.
     */
    @Transactional
    fun confirmPack(
        months: Int,
        settledGuests: Long,
    ) {
        if (subscriptionProperties.period(months) == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported prepaid period: $months months")
        }
        val now = Instant.now()
        val row = packs.findFirstByOrderByStartedAtAsc()
        if (row == null) {
            packs.save(OrganizerPack(startedAt = now, expiresAt = plusMonths(now, months.toLong())))
            return
        }
        if (row.expiresAt.isAfter(now)) {
            row.expiresAt = plusMonths(row.expiresAt, months.toLong())
        } else {
            row.startedAt = now
            row.expiresAt = plusMonths(now, months.toLong())
        }
        row.owedGuests = (row.owedGuests - settledGuests).coerceAtLeast(0)
        row.updatedAt = now
    }

    /** Extra guests were paid for: they add to the current month's allowance. */
    @Transactional
    fun confirmExtra(guests: Long) {
        val now = Instant.now()
        val row = packs.findFirstByOrderByStartedAtAsc() ?: return
        val month = currentMonth(row, now)
        month.extraGuests += guests
    }

    private fun activePack(now: Instant): OrganizerPack? = packs.findFirstByOrderByStartedAtAsc()?.takeIf { it.expiresAt.isAfter(now) }

    private fun currentMonth(
        row: OrganizerPack,
        now: Instant,
    ): OrganizerPackMonth {
        val start = monthStart(row, now)
        return packMonths.findFirstByMonthStart(start) ?: packMonths.save(OrganizerPackMonth(start))
    }

    /** Start of the pack month [now] falls in, months counted from the pack's start. */
    private fun monthStart(
        row: OrganizerPack,
        now: Instant,
    ): Instant {
        val start = row.startedAt.truncatedTo(ChronoUnit.SECONDS)
        var elapsed = ChronoUnit.MONTHS.between(start.atZone(ZoneOffset.UTC), now.atZone(ZoneOffset.UTC)).coerceAtLeast(0)
        while (elapsed > 0 && plusMonths(start, elapsed).isAfter(now)) elapsed--
        return plusMonths(start, elapsed)
    }

    /** Within a day of the event's start: sending never stops then (ADR 105). */
    private fun isEventDay(
        eventId: UUID,
        now: Instant,
    ): Boolean {
        val start = events.findEvent(eventId)?.startDateTime ?: return false
        return Duration.between(now, start).abs() <= EVENT_DAY
    }

    private fun plusMonths(
        instant: Instant,
        months: Long,
    ): Instant = instant.atZone(ZoneOffset.UTC).plusMonths(months).toInstant()

    private companion object {
        val EVENT_DAY: Duration = Duration.ofHours(24)
    }
}

data class PackQuote(
    val amountMinor: Long,
    val currency: String,
    /** Guests still owed that this renewal pays for. */
    val owedGuests: Long = 0,
    /** Extra guests this purchase adds. */
    val guests: Long = 0,
)

/** The organization's Organizer Pack and its current month (ADR 105), prices in its billing currency. */
data class PackView(
    val active: Boolean,
    val startedAt: Instant?,
    val expiresAt: Instant?,
    val monthStart: Instant?,
    val monthEnd: Instant?,
    val includedGuests: Long,
    val extraGuests: Long,
    val usedGuests: Long,
    val remainingGuests: Long,
    /** Guests sent past the allowance on an event's day, paid with the next renewal. */
    val owedGuests: Long,
    val currency: String,
    val monthlyMinor: Long,
    val extraPerGuestMinor: Long,
    val extraBlockGuests: Long,
    val owedMinor: Long,
    val months: List<MonthOption>,
)
