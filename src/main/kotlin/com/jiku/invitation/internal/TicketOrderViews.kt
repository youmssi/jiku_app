package com.jiku.invitation.internal

import com.jiku.tenant.TenantPaymentMethodsInfo
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.Size
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Instant
import java.util.UUID

/** Limits of a ticket order (JIKU-177). */
@ConfigurationProperties(prefix = "sales.orders")
data class TicketOrderProperties(
    /** Tickets one order may hold, across its categories. */
    val maxTickets: Int = 10,
)

data class OrderLineRequest(
    val ticketTypeId: UUID,
    @field:Min(1) val quantity: Int,
)

data class PlaceOrderRequest(
    @field:NotEmpty @field:Valid val lines: List<OrderLineRequest>,
    @field:NotBlank @field:Size(max = 120) val buyerName: String,
    @field:NotBlank @field:Size(max = 32) val buyerPhone: String,
    @field:Email @field:Size(max = 254) val buyerEmail: String? = null,
)

data class DeclarePaymentRequest(
    @field:NotBlank @field:Size(max = 80) val paymentReference: String,
)

data class RejectOrderRequest(
    @field:NotBlank @field:Size(max = 300) val reason: String,
)

/** Why the event cannot be bought right now; null in [PublicSaleView.closedReason] when it can. */
enum class SaleClosedReason {
    NOT_PUBLISHED,
    CANCELLED,
    ENDED,
    NOTHING_FOR_SALE,
    ORGANIZER_NOT_VERIFIED,
    NO_PAYMENT_METHOD,

    /** Every category waits for the organizer's next commission batch (JIKU-178). */
    PAUSED,
}

data class PublicSaleCategoryView(
    val id: UUID,
    val label: String,
    val colorHex: String,
    val priceMinor: Long,
    val currency: String,
    /** Places still available in this category; null when there is no limit. */
    val available: Int?,
    /** On sale again soon: the organizer's commission batch for this category is used up (JIKU-178). */
    val paused: Boolean = false,
)

/** What the public event page needs to sell tickets (JIKU-177). */
data class PublicSaleView(
    val eventId: UUID,
    val eventName: String,
    val eventStart: Instant?,
    val eventEnd: Instant?,
    val eventTimezone: String,
    val eventLocation: String?,
    val organizerName: String,
    val logoUrl: String?,
    val primaryColor: String,
    /** The organizer's approved verification (COMPANY, PERSONAL); required to sell. */
    val organizerVerification: String?,
    val categories: List<PublicSaleCategoryView>,
    val maxTicketsPerOrder: Int,
    /** How long an unpaid order keeps its places, in minutes. */
    val holdMinutes: Long,
    val onSale: Boolean,
    val closedReason: SaleClosedReason?,
)

data class OrderLineView(
    val ticketTypeId: UUID,
    val label: String,
    val quantity: Int,
    val unitPriceMinor: Long,
)

/** The buyer's view of its order, reached by the link token (JIKU-177). */
data class OrderView(
    val reference: String,
    val status: TicketOrderStatus,
    val eventId: UUID,
    val eventName: String,
    val eventStart: Instant?,
    val eventTimezone: String,
    val organizerName: String,
    val organizerVerification: String?,
    val buyerName: String,
    val lines: List<OrderLineView>,
    val totalMinor: Long,
    val currency: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val declaredAt: Instant?,
    val paymentReference: String?,
    val rejectionReason: String?,
    /** Where to pay; shown while the order waits for payment. */
    val paymentMethods: TenantPaymentMethodsInfo?,
    /** One link token per ticket, to open it; present once the order is paid. */
    val ticketTokens: List<String>,
)

data class PlacedOrderView(
    /** The buyer's link to the order; keep it, it is the only way back. */
    val token: String,
    val order: OrderView,
)

/** An order as the organization sees it in the event's sales. */
data class OrganizerOrderView(
    val id: UUID,
    val reference: String,
    val status: TicketOrderStatus,
    val buyerName: String,
    val buyerPhone: String,
    val buyerEmail: String?,
    val lines: List<OrderLineView>,
    val ticketCount: Int,
    val totalMinor: Long,
    val currency: String,
    val createdAt: Instant,
    val expiresAt: Instant,
    val declaredAt: Instant?,
    val paymentReference: String?,
    val decidedAt: Instant?,
    val rejectionReason: String?,
)
