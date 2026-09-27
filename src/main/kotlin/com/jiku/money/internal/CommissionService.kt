package com.jiku.money.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.TicketTypeInfo
import com.jiku.shared.CommissionGate
import com.jiku.shared.VerificationGate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** How the organizer wants to open a batch. */
enum class CommissionBatchMode {
    FREE,
    CREDIT,
    PAY,
}

data class CommissionCategoryView(
    val ticketTypeId: UUID,
    val label: String,
    val priceMinor: Long,
    val unitCommissionMinor: Long,
    /** Tickets the category's live batches still cover. */
    val covered: Int,
    /** Tickets sold under a batch so far, overage included. */
    val sold: Int,
    /** A batch of this category waits for its payment. */
    val pendingPayment: Boolean,
    /** Places the next batch would cover; zero when every place is covered. */
    val nextBatchSize: Int,
)

/** The commission side of an event's sale (JIKU-178), for its organizer. */
data class CommissionOverview(
    val eventId: UUID,
    val currency: String,
    /** The rate as a percentage, e.g. "3". */
    val ratePercent: String,
    val batchSize: Int,
    /** The event's day: sales never pause, what is sold beyond the batches is owed after. */
    val onEventDay: Boolean,
    val categories: List<CommissionCategoryView>,
    /** Commission still owed: a credit batch not settled, or tickets sold beyond the batches. */
    val owedMinor: Long,
    /** Credit left from unused batches, deducted from the next payment. */
    val creditMinor: Long,
    val freeBatchAvailable: Boolean,
    val creditBatchAvailable: Boolean,
)

data class CommissionQuote(
    val ticketTypeId: UUID,
    val size: Int,
    val unitCommissionMinor: Long,
    val amountMinor: Long,
    val owedMinor: Long,
    val creditAppliedMinor: Long,
    /** What paying now costs: the batch, plus what is owed, less the credit. */
    val totalMinor: Long,
    val currency: String,
    val freeBatchAvailable: Boolean,
    val creditBatchAvailable: Boolean,
)

data class OpenBatchRequest(
    val ticketTypeId: UUID,
    val mode: CommissionBatchMode,
)

/** A batch just opened: live at once, or waiting for [totalMinor] to be paid. */
data class OpenedBatch(
    val batch: CommissionBatch,
    val totalMinor: Long,
)

/**
 * The commission on tickets sold (JIKU-178; ADR 104 §8, ADR 105 decision 4).
 *
 * - A batch covers the next tickets of one category, never more than its
 *   places left, at the rate of the category's price.
 * - The organization's first batch is free; a verified organization may keep
 *   one batch on credit, settled with its next payment.
 * - A sale pauses when its batches are used up, except on the event's day:
 *   then it goes on, and what no batch covers is owed after the event.
 * - When the event is over, the unused part of a paid batch becomes a credit,
 *   deducted from the next payments; a credit batch then owes only what it used.
 */
@Service
class CommissionService(
    private val batches: CommissionBatchRepository,
    private val credits: CommissionCreditRepository,
    private val events: EventModuleApi,
    private val verification: VerificationGate,
    private val properties: CommissionProperties,
) {
    @Transactional(readOnly = true)
    fun overview(
        eventId: UUID,
        now: Instant = Instant.now(),
    ): CommissionOverview {
        val event = loadEvent(eventId)
        val eventBatches = batches.findByEventIdOrderByCreatedAtAsc(eventId)
        val priced = events.ticketTypes(eventId).filter { it.priceMinor != null }
        val owed = owed()
        return CommissionOverview(
            eventId = eventId,
            currency = priced.firstOrNull()?.currency ?: "",
            ratePercent =
                properties.rate
                    .movePointRight(2)
                    .stripTrailingZeros()
                    .toPlainString(),
            batchSize = properties.batchSize,
            onEventDay = isEventDay(event, now),
            categories =
                priced.map { type ->
                    val ofType = eventBatches.filter { it.ticketTypeId == type.id }
                    CommissionCategoryView(
                        ticketTypeId = type.id,
                        label = type.label,
                        priceMinor = requireNotNull(type.priceMinor),
                        unitCommissionMinor = unitCommission(type),
                        covered = ofType.filter { it.isLive() }.sumOf { it.remaining },
                        sold = ofType.filter { it.status != CommissionBatchStatus.CANCELLED }.sumOf { it.consumed },
                        pendingPayment = ofType.any { it.status == CommissionBatchStatus.PENDING_PAYMENT },
                        nextBatchSize = nextBatchSize(type, ofType),
                    )
                },
            owedMinor = owed,
            creditMinor = usableCredit(now),
            freeBatchAvailable = freeAvailable(owed),
            creditBatchAvailable = creditAvailable(owed),
        )
    }

    @Transactional(readOnly = true)
    fun quote(
        eventId: UUID,
        ticketTypeId: UUID,
        now: Instant = Instant.now(),
    ): CommissionQuote {
        loadEvent(eventId)
        val type = loadPricedType(eventId, ticketTypeId)
        val size = nextBatchSize(type, batches.findByEventIdOrderByCreatedAtAsc(eventId).filter { it.ticketTypeId == type.id })
        val unit = unitCommission(type)
        val amount = unit * size
        val owed = owed()
        val credit = minOf(usableCredit(now), amount + owed)
        return CommissionQuote(
            ticketTypeId = type.id,
            size = size,
            unitCommissionMinor = unit,
            amountMinor = amount,
            owedMinor = owed,
            creditAppliedMinor = credit,
            totalMinor = amount + owed - credit,
            currency = requireNotNull(type.currency),
            freeBatchAvailable = freeAvailable(owed),
            creditBatchAvailable = creditAvailable(owed),
        )
    }

    /**
     * Opens the category's next batch. A free or credit batch is live at once;
     * a paid one is live once [totalMinor][OpenedBatch.totalMinor] is paid, or at
     * once when the credit covers it all.
     */
    @Transactional
    fun open(
        eventId: UUID,
        request: OpenBatchRequest,
        now: Instant = Instant.now(),
    ): OpenedBatch {
        val event = loadEvent(eventId)
        if (event.status == EventInfo.STATUS_CANCELLED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This event has been cancelled")
        }
        val quote = quote(eventId, request.ticketTypeId, now)
        if (quote.size <= 0) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Every place of this category is already covered")
        }
        when (request.mode) {
            CommissionBatchMode.FREE ->
                if (!quote.freeBatchAvailable) throw ResponseStatusException(HttpStatus.CONFLICT, "The free batch was already used")
            CommissionBatchMode.CREDIT ->
                if (!quote.creditBatchAvailable) {
                    throw ResponseStatusException(HttpStatus.CONFLICT, "A batch on credit is not available; pay this one")
                }
            CommissionBatchMode.PAY -> Unit
        }
        batches
            .findByEventIdOrderByCreatedAtAsc(eventId)
            .filter { it.ticketTypeId == request.ticketTypeId && it.status == CommissionBatchStatus.PENDING_PAYMENT }
            .forEach { it.status = CommissionBatchStatus.CANCELLED }

        val batch =
            CommissionBatch(
                eventId = eventId,
                ticketTypeId = request.ticketTypeId,
                funding =
                    when (request.mode) {
                        CommissionBatchMode.FREE -> CommissionFunding.FREE
                        CommissionBatchMode.CREDIT -> CommissionFunding.CREDIT
                        CommissionBatchMode.PAY -> CommissionFunding.PAID
                    },
                size = quote.size,
                unitCommissionMinor = quote.unitCommissionMinor,
                currency = quote.currency,
                closesAt = closesAt(event, now),
                createdAt = now,
            ).apply {
                amountMinor = quote.amountMinor
                if (request.mode == CommissionBatchMode.CREDIT) owedMinor = quote.amountMinor
            }
        if (request.mode != CommissionBatchMode.PAY) return OpenedBatch(batches.save(batch), 0)

        batch.creditAppliedMinor = quote.creditAppliedMinor
        if (quote.totalMinor == 0L) {
            val saved = batches.save(batch)
            settle(saved, now)
            return OpenedBatch(saved, 0)
        }
        batch.status = CommissionBatchStatus.PENDING_PAYMENT
        return OpenedBatch(batches.save(batch), quote.totalMinor)
    }

    /** The payment of a batch succeeded: it goes live, what was owed is settled and the credit it used is spent. */
    @Transactional
    fun activatePaid(
        batchId: UUID,
        now: Instant = Instant.now(),
    ) {
        val batch = batches.findById(batchId).orElse(null) ?: return
        if (batch.status == CommissionBatchStatus.PENDING_PAYMENT || batch.status == CommissionBatchStatus.CANCELLED) {
            batch.status = if (batch.closesAt.isAfter(now)) CommissionBatchStatus.ACTIVE else CommissionBatchStatus.CLOSED
            settle(batches.save(batch), now)
        }
    }

    /** Tickets the category's batches still cover, or null on the event's day, when sales never pause. */
    @Transactional(readOnly = true)
    fun coveredPlaces(
        eventId: UUID,
        ticketTypeId: UUID,
        now: Instant = Instant.now(),
    ): Int? {
        val event = events.findEvent(eventId) ?: return 0
        if (isEventDay(event, now)) return null
        return batches
            .findByEventIdOrderByCreatedAtAsc(eventId)
            .filter { it.ticketTypeId == ticketTypeId && it.isLive() }
            .sumOf { it.remaining }
    }

    /** Consumes [quantity] places, oldest batch first; what no batch covers goes to the category's overage. */
    @Transactional
    fun consume(
        eventId: UUID,
        ticketTypeId: UUID,
        quantity: Int,
        now: Instant = Instant.now(),
    ) {
        var left = quantity
        val live = batches.lockActive(eventId, ticketTypeId)
        for (batch in live.filter { it.funding != CommissionFunding.OVERAGE }) {
            if (left == 0) break
            val taken = minOf(batch.remaining, left)
            batch.consumed += taken
            left -= taken
        }
        if (left > 0) {
            val type = loadPricedType(eventId, ticketTypeId)
            val overage =
                live.firstOrNull { it.funding == CommissionFunding.OVERAGE }
                    ?: CommissionBatch(
                        eventId = eventId,
                        ticketTypeId = ticketTypeId,
                        funding = CommissionFunding.OVERAGE,
                        size = 0,
                        unitCommissionMinor = unitCommission(type),
                        currency = requireNotNull(type.currency),
                        closesAt = closesAt(loadEvent(eventId), now),
                        createdAt = now,
                    )
            overage.size += left
            overage.consumed += left
            overage.amountMinor = overage.unitCommissionMinor * overage.size
            overage.owedMinor = overage.amountMinor
            batches.save(overage)
        }
        batches.saveAll(live)
    }

    /** Closes the tenant's batches of events that are over; returns how many. */
    @Transactional
    fun closeDue(now: Instant = Instant.now()): Int {
        val due = batches.findDueToClose(now)
        batches.findAllById(due).forEach { close(it, now) }
        return due.size
    }

    private fun close(
        batch: CommissionBatch,
        now: Instant,
    ) {
        if (batch.status == CommissionBatchStatus.PENDING_PAYMENT) {
            batch.status = CommissionBatchStatus.CANCELLED
            batches.save(batch)
            return
        }
        batch.status = CommissionBatchStatus.CLOSED
        batch.closedAt = now
        when (batch.funding) {
            CommissionFunding.PAID -> {
                val unused = batch.remaining * batch.unitCommissionMinor
                if (unused > 0) {
                    credits.save(
                        CommissionCredit(
                            sourceBatchId = requireNotNull(batch.id),
                            amountMinor = unused,
                            currency = batch.currency,
                            expiresAt = now.plus(properties.creditValidity),
                            createdAt = now,
                        ),
                    )
                }
            }
            CommissionFunding.CREDIT, CommissionFunding.OVERAGE ->
                if (batch.settledAt == null) {
                    batch.owedMinor = batch.consumed * batch.unitCommissionMinor
                    if (batch.owedMinor == 0L) batch.settledAt = now
                }
            CommissionFunding.FREE -> Unit
        }
        batches.save(batch)
    }

    /**
     * A paid batch just went live: every commission still owed is settled with
     * it, and the credit its payment used is spent. A credit batch still open
     * is settled in full and becomes a paid one.
     */
    private fun settle(
        paid: CommissionBatch,
        now: Instant,
    ) {
        batches.findUnsettled().filter { it.id != paid.id && it.settleable() }.forEach { owedBatch ->
            if (owedBatch.funding == CommissionFunding.CREDIT && owedBatch.status == CommissionBatchStatus.ACTIVE) {
                owedBatch.funding = CommissionFunding.PAID
            }
            owedBatch.owedMinor = 0
            owedBatch.settledAt = now
            batches.save(owedBatch)
        }
        var toSpend = paid.creditAppliedMinor
        for (credit in credits.findUsable(now)) {
            if (toSpend == 0L) break
            val used = minOf(credit.remainingMinor, toSpend)
            credit.remainingMinor -= used
            toSpend -= used
            credits.save(credit)
        }
    }

    /** What is owed now: open credit batches in full, closed credit and overage batches for what they used. */
    private fun owed(): Long = batches.findUnsettled().filter { it.settleable() }.sumOf { it.owedMinor }

    /** Overage of an event still running is owed only once the event is over. */
    private fun CommissionBatch.settleable(): Boolean = !(funding == CommissionFunding.OVERAGE && status == CommissionBatchStatus.ACTIVE)

    private fun usableCredit(now: Instant): Long = credits.findUsable(now).sumOf { it.remainingMinor }

    private fun freeAvailable(owed: Long): Boolean =
        owed == 0L && !batches.existsByFundingIn(listOf(CommissionFunding.FREE, CommissionFunding.PAID, CommissionFunding.CREDIT))

    private fun creditAvailable(owed: Long): Boolean =
        owed == 0L && verification.isVerified() && batches.findUnsettled().none { it.funding == CommissionFunding.CREDIT }

    private fun nextBatchSize(
        type: TicketTypeInfo,
        ofType: List<CommissionBatch>,
    ): Int {
        val covered =
            ofType
                .filter { it.funding != CommissionFunding.OVERAGE && it.status != CommissionBatchStatus.CANCELLED }
                .sumOf { it.size }
        val left = type.maxCapacity?.let { it - covered }
        return minOf(properties.batchSize, left ?: properties.batchSize).coerceAtLeast(0)
    }

    private fun unitCommission(type: TicketTypeInfo): Long =
        BigDecimal
            .valueOf(requireNotNull(type.priceMinor))
            .multiply(properties.rate)
            .setScale(0, RoundingMode.HALF_UP)
            .toLong()

    private fun CommissionBatch.isLive(): Boolean = status == CommissionBatchStatus.ACTIVE && funding != CommissionFunding.OVERAGE

    /** The event's days, in its own time zone, from its start to its end. */
    private fun isEventDay(
        event: EventInfo,
        now: Instant,
    ): Boolean {
        val start = event.startDateTime ?: return false
        val zone = ZoneId.of(event.timezone)
        val today = LocalDate.ofInstant(now, zone)
        val first = LocalDate.ofInstant(start, zone)
        val last = LocalDate.ofInstant(event.endDateTime ?: start, zone)
        return !today.isBefore(first) && !today.isAfter(last)
    }

    /** A batch closes when the event's last day ends, in its time zone. */
    private fun closesAt(
        event: EventInfo,
        now: Instant,
    ): Instant {
        val last = event.endDateTime ?: event.startDateTime ?: return now.plus(properties.creditValidity)
        val zone = ZoneId.of(event.timezone)
        return LocalDate
            .ofInstant(last, zone)
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
    }

    private fun loadEvent(eventId: UUID): EventInfo =
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    private fun loadPricedType(
        eventId: UUID,
        ticketTypeId: UUID,
    ): TicketTypeInfo =
        events.ticketTypes(eventId).firstOrNull { it.id == ticketTypeId && it.priceMinor != null }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This ticket category is not sold")
}

/** The ticket-sale flow's view of the commission (shared [CommissionGate]). */
@Component
class CommissionGateAdapter(
    private val commission: CommissionService,
) : CommissionGate {
    override fun coveredPlaces(
        eventId: UUID,
        ticketTypeId: UUID,
    ): Int? = commission.coveredPlaces(eventId, ticketTypeId)

    override fun consume(
        eventId: UUID,
        ticketTypeId: UUID,
        quantity: Int,
    ) = commission.consume(eventId, ticketTypeId, quantity)
}
