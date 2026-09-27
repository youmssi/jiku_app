package com.jiku.invitation.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.TicketTypeInfo
import com.jiku.shared.ClientCharge
import com.jiku.shared.TenantContext
import com.jiku.shared.VerificationGate
import com.jiku.tenant.TenantInfo
import com.jiku.tenant.TenantModuleApi
import com.jiku.ticket.TicketPaymentMethod
import com.jiku.ticket.TicketingModuleApi
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Public ticket sales (JIKU-177, plan de production 6.1): an order takes its
 * places at once, then waits for the client to pay the organization.
 *
 * - Places: every category of the order is reserved under the event's and the
 *   category's capacities in the order's transaction; one full category rolls
 *   the whole order back, so a buyer never gets half an order.
 * - Hold: an order the client has not declared paid gives its places back once
 *   the organization's hold time passes ([TicketOrderExpiryJob]).
 * - Payment: the client declares it with the transaction reference; the
 *   organization confirms (tickets issued, in the buyer's name, already paid)
 *   or refuses with a reason (places given back).
 *
 * Every step runs under the tenant bound by the caller (the order link, the
 * organization's username, or the organizer's session).
 */
@Service
class TicketOrderService(
    private val orders: TicketOrderRepository,
    private val lines: TicketOrderLineRepository,
    private val guests: GuestRepository,
    private val events: EventModuleApi,
    private val tenants: TenantModuleApi,
    private val ticketing: TicketingModuleApi,
    private val verification: VerificationGate,
    private val invitationTokens: InvitationTokenService,
    private val orderTokens: OrderTokenService,
    private val properties: TicketOrderProperties,
) {
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    fun publicSale(
        tenant: TenantInfo,
        eventId: UUID,
        now: Instant = Instant.now(),
    ): PublicSaleView {
        val event = loadPublicEvent(eventId)
        val types = events.ticketTypes(eventId)
        val categories = saleCategories(types)
        val remaining = eventRemaining(event.id)
        return PublicSaleView(
            eventId = event.id,
            eventName = event.name,
            eventStart = event.startDateTime,
            eventEnd = event.endDateTime,
            eventTimezone = event.timezone,
            eventLocation = event.location,
            organizerName = event.brand.name ?: tenant.displayName,
            logoUrl = event.brand.logoUrl ?: tenant.logoUrl,
            primaryColor = event.brand.primaryColor ?: tenant.primaryColor,
            organizerVerification = verification.verifiedKind(),
            categories =
                categories.map { (type, charge) ->
                    PublicSaleCategoryView(
                        id = type.id,
                        label = type.label,
                        colorHex = type.colorHex,
                        priceMinor = charge.amountMinor,
                        currency = charge.currency,
                        available = available(type, remaining),
                    )
                },
            maxTicketsPerOrder = properties.maxTickets,
            holdMinutes = tenants.orderHold(tenant.id).toMinutes(),
            onSale = closedReason(event, tenant, categories.isNotEmpty(), now) == null,
            closedReason = closedReason(event, tenant, categories.isNotEmpty(), now),
        )
    }

    @Transactional
    fun place(
        tenant: TenantInfo,
        eventId: UUID,
        request: PlaceOrderRequest,
        now: Instant = Instant.now(),
    ): PlacedOrderView {
        val event = loadPublicEvent(eventId)
        val categories = saleCategories(events.ticketTypes(eventId)).associateBy { it.first.id }
        closedReason(event, tenant, categories.isNotEmpty(), now)?.let {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Tickets for this event are not on sale ($it)")
        }
        val quantities =
            request.lines
                .groupBy { it.ticketTypeId }
                .mapValues { (_, same) -> same.sumOf { it.quantity } }
        val total = quantities.values.sum()
        if (total < 1 || total > properties.maxTickets) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "An order holds between 1 and ${properties.maxTickets} tickets")
        }
        val priced =
            quantities.map { (typeId, quantity) ->
                val (type, charge) =
                    categories[typeId] ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This ticket category is not on sale")
                Triple(type, charge, quantity)
            }
        val currency = priced.map { it.second.currency }.distinct().singleOrNull()
        if (currency == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "An order is paid in one currency")
        }
        for ((type, _, quantity) in priced) {
            if (!events.reserveAttendanceSlots(eventId, type.id, quantity)) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Not enough places left in ${type.label}")
            }
        }
        val order =
            orders.save(
                TicketOrder(
                    eventId = eventId,
                    reference = newReference(),
                    buyerName = request.buyerName.trim(),
                    buyerPhone = request.buyerPhone.filterNot { it == ' ' },
                    buyerEmail = request.buyerEmail?.trim()?.takeIf { it.isNotEmpty() },
                    totalMinor = priced.sumOf { (_, charge, quantity) -> charge.amountMinor * quantity },
                    currency = currency,
                    expiresAt = now.plus(tenants.orderHold(tenant.id)),
                    createdAt = now,
                ),
            )
        val orderId = requireNotNull(order.id)
        lines.saveAll(
            priced.map { (type, charge, quantity) ->
                TicketOrderLine(orderId = orderId, ticketTypeId = type.id, quantity = quantity, unitPriceMinor = charge.amountMinor)
            },
        )
        return PlacedOrderView(token = orderTokens.issue(orderId, tenant.id.toString()), order = buyerView(order, tenant))
    }

    @Transactional(readOnly = true)
    fun view(orderId: UUID): OrderView = buyerView(load(orderId), currentTenant())

    /**
     * The client says it paid. The order stops expiring: only the organization
     * can tell whether the money arrived, and it must not lose the places while
     * it checks. Declaring again is refused rather than overwriting the reference.
     */
    @Transactional
    fun declare(
        orderId: UUID,
        paymentReference: String,
        now: Instant = Instant.now(),
    ): OrderView {
        val order = load(orderId)
        if (orders.declare(orderId, paymentReference.trim(), now) != 1) {
            val message =
                when (order.status) {
                    TicketOrderStatus.AWAITING_PAYMENT -> "This order has expired; its places were given back"
                    TicketOrderStatus.DECLARED -> "The payment of this order was already declared"
                    else -> "This order is ${order.status.name.lowercase()}"
                }
            throw ResponseStatusException(HttpStatus.CONFLICT, message)
        }
        return view(orderId)
    }

    @Transactional(readOnly = true)
    fun list(
        eventId: UUID,
        status: TicketOrderStatus?,
    ): List<OrganizerOrderView> {
        val found =
            if (status == null) {
                orders.findByEventIdOrderByCreatedAtDesc(eventId)
            } else {
                orders.findByEventIdAndStatusOrderByCreatedAtDesc(eventId, status)
            }
        val linesByOrder = lines.findByOrderIdIn(found.mapNotNull { it.id }).groupBy { it.orderId }
        val labels = events.ticketTypes(eventId).associate { it.id to it.label }
        return found.map { it.toOrganizerView(linesByOrder[it.id].orEmpty(), labels) }
    }

    /**
     * The organization received the money: the order is paid and its tickets
     * are issued, one guest per ticket in the buyer's name, already marked paid.
     * The places were taken when the order was placed, so none is taken again.
     */
    @Transactional
    fun confirm(
        eventId: UUID,
        orderId: UUID,
        decidedBy: String?,
        now: Instant = Instant.now(),
    ): OrganizerOrderView {
        val order = loadForEvent(eventId, orderId)
        if (orders.transition(orderId, HOLDING, TicketOrderStatus.PAID, now, decidedBy) != 1) {
            throw decided(order)
        }
        val orderLines = lines.findByOrderId(orderId)
        val types = events.ticketTypes(eventId).associateBy { it.id }
        val firstName = order.buyerName.substringBefore(' ')
        val lastName = order.buyerName.substringAfter(' ', "").trim()
        for (line in orderLines) {
            repeat(line.quantity) {
                val guest =
                    guests.save(
                        Guest(
                            eventId = eventId,
                            firstName = firstName,
                            lastName = lastName,
                            email = order.buyerEmail,
                            phoneNumber = order.buyerPhone,
                        ).apply {
                            rsvpStatus = RsvpStatus.CONFIRMED
                            ticketTypeId = line.ticketTypeId
                            excludedFromInvitations = true
                            origin = GuestOrigin.PURCHASED
                            this.orderId = orderId
                        },
                    )
                val charge = ClientCharge(line.unitPriceMinor, order.currency)
                val ticket = ticketing.issueTicket(eventId, requireNotNull(guest.id), line.ticketTypeId, charge)
                ticketing.markPaidByCode(ticket.ticketCode, TicketPaymentMethod.MOBILE_MONEY, decidedBy ?: ORDER_PAYER)
            }
        }
        val labels = types.mapValues { it.value.label }
        return load(orderId).toOrganizerView(orderLines, labels)
    }

    @Transactional
    fun reject(
        eventId: UUID,
        orderId: UUID,
        reason: String,
        decidedBy: String?,
        now: Instant = Instant.now(),
    ): OrganizerOrderView {
        val order = loadForEvent(eventId, orderId)
        if (orders.transition(orderId, HOLDING, TicketOrderStatus.REJECTED, now, decidedBy) != 1) {
            throw decided(order)
        }
        val refused = load(orderId).apply { rejectionReason = reason.trim().take(300) }
        orders.save(refused)
        val orderLines = lines.findByOrderId(orderId)
        releasePlaces(refused, orderLines)
        return refused.toOrganizerView(orderLines, events.ticketTypes(eventId).associate { it.id to it.label })
    }

    /** Expires the tenant's unpaid orders past their hold and gives their places back; returns how many. */
    @Transactional
    fun expireOverdue(now: Instant = Instant.now()): Int {
        var expired = 0
        for (orderId in orders.findExpiredIds(now)) {
            if (orders.transition(orderId, listOf(TicketOrderStatus.AWAITING_PAYMENT), TicketOrderStatus.EXPIRED, now, null) == 1) {
                releasePlaces(load(orderId), lines.findByOrderId(orderId))
                expired++
            }
        }
        return expired
    }

    private fun releasePlaces(
        order: TicketOrder,
        orderLines: List<TicketOrderLine>,
    ) {
        orderLines.forEach { events.releaseAttendanceSlots(order.eventId, it.ticketTypeId, it.quantity) }
    }

    private fun buyerView(
        order: TicketOrder,
        tenant: TenantInfo,
    ): OrderView {
        val orderId = requireNotNull(order.id)
        val event = events.findEvent(order.eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")
        val labels = events.ticketTypes(order.eventId).associate { it.id to it.label }
        val tickets =
            if (order.status == TicketOrderStatus.PAID) {
                guests.findByOrderIdOrderByCreatedAtAsc(orderId).map {
                    invitationTokens.issue(requireNotNull(it.id), order.eventId, tenant.id.toString())
                }
            } else {
                emptyList()
            }
        return OrderView(
            reference = order.reference,
            status = order.status,
            eventId = order.eventId,
            eventName = event.name,
            eventStart = event.startDateTime,
            eventTimezone = event.timezone,
            organizerName = event.brand.name ?: tenant.displayName,
            organizerVerification = verification.verifiedKind(),
            buyerName = order.buyerName,
            lines = lines.findByOrderId(orderId).map { it.toView(labels) },
            totalMinor = order.totalMinor,
            currency = order.currency,
            createdAt = order.createdAt,
            expiresAt = order.expiresAt,
            declaredAt = order.declaredAt,
            paymentReference = order.paymentReference,
            rejectionReason = order.rejectionReason,
            paymentMethods = if (order.status.holdsPlaces) tenant.paymentMethods else null,
            ticketTokens = tickets,
        )
    }

    private fun closedReason(
        event: EventInfo,
        tenant: TenantInfo,
        anythingForSale: Boolean,
        now: Instant,
    ): SaleClosedReason? =
        when {
            event.status == EventInfo.STATUS_CANCELLED -> SaleClosedReason.CANCELLED
            event.status != EventInfo.STATUS_PUBLISHED -> SaleClosedReason.NOT_PUBLISHED
            (event.endDateTime ?: event.startDateTime)?.isBefore(now) == true -> SaleClosedReason.ENDED
            !anythingForSale -> SaleClosedReason.NOTHING_FOR_SALE
            !verification.isVerified() -> SaleClosedReason.ORGANIZER_NOT_VERIFIED
            tenant.paymentMethods == null -> SaleClosedReason.NO_PAYMENT_METHOD
            else -> null
        }

    /** Categories with a price: a free category is given by invitation, never sold. */
    private fun saleCategories(types: List<TicketTypeInfo>): List<Pair<TicketTypeInfo, ClientCharge>> =
        types.mapNotNull { type -> type.clientCharge()?.let { type to it } }

    private fun eventRemaining(eventId: UUID): Int? = events.remainingAttendance(eventId)

    private fun available(
        type: TicketTypeInfo,
        eventRemaining: Int?,
    ): Int? {
        val own = type.maxCapacity?.let { (it - type.confirmedCount).coerceAtLeast(0) }
        return listOfNotNull(own, eventRemaining).minOrNull()
    }

    private fun loadPublicEvent(eventId: UUID): EventInfo =
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")

    private fun load(orderId: UUID): TicketOrder =
        orders.findById(orderId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found") }

    private fun loadForEvent(
        eventId: UUID,
        orderId: UUID,
    ): TicketOrder =
        load(orderId).takeIf { it.eventId == eventId } ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")

    private fun currentTenant(): TenantInfo =
        TenantContext.get()?.let { tenants.findTenant(UUID.fromString(it)) }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")

    private fun decided(order: TicketOrder): ResponseStatusException =
        ResponseStatusException(
            HttpStatus.CONFLICT,
            when (order.status) {
                TicketOrderStatus.PAID -> "This order is already paid"
                TicketOrderStatus.REJECTED -> "This order was already refused"
                else -> "This order has expired; its places were given back"
            },
        )

    private fun newReference(): String {
        repeat(REFERENCE_ATTEMPTS) {
            val candidate = (1..REFERENCE_LENGTH).map { REFERENCE_ALPHABET[random.nextInt(REFERENCE_ALPHABET.length)] }.joinToString("")
            if (!orders.existsByReference(candidate)) return candidate
        }
        throw IllegalStateException("Could not draw a free order reference")
    }

    private fun TicketOrderLine.toView(labels: Map<UUID, String>): OrderLineView =
        OrderLineView(ticketTypeId = ticketTypeId, label = labels[ticketTypeId] ?: "", quantity = quantity, unitPriceMinor = unitPriceMinor)

    private fun TicketOrder.toOrganizerView(
        orderLines: List<TicketOrderLine>,
        labels: Map<UUID, String>,
    ): OrganizerOrderView =
        OrganizerOrderView(
            id = requireNotNull(id),
            reference = reference,
            status = status,
            buyerName = buyerName,
            buyerPhone = buyerPhone,
            buyerEmail = buyerEmail,
            lines = orderLines.map { it.toView(labels) },
            ticketCount = orderLines.sumOf { it.quantity },
            totalMinor = totalMinor,
            currency = currency,
            createdAt = createdAt,
            expiresAt = expiresAt,
            declaredAt = declaredAt,
            paymentReference = paymentReference,
            decidedAt = decidedAt,
            rejectionReason = rejectionReason,
        )

    private companion object {
        val HOLDING = listOf(TicketOrderStatus.AWAITING_PAYMENT, TicketOrderStatus.DECLARED)
        const val ORDER_PAYER = "order"
        const val REFERENCE_LENGTH = 8

        /** No 0/O, 1/I/L: the client reads the reference aloud or types it into a Mobile Money message. */
        const val REFERENCE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
        const val REFERENCE_ATTEMPTS = 5
    }
}
