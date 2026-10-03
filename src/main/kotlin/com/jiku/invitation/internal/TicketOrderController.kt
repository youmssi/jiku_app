package com.jiku.invitation.internal

import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantInfo
import com.jiku.tenant.TenantModuleApi
import io.jsonwebtoken.Claims
import jakarta.validation.Valid
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * The public side of ticket sales (JIKU-177), without an account: the event's
 * sale page and placing an order, found by the organization's username, then
 * the order itself, reached by its link token. Each binds the tenant it
 * resolves before the transactional service runs.
 */
@RestController
class PublicTicketOrderController(
    private val tenants: TenantModuleApi,
    private val tenantAccessGate: TenantAccessGate,
    private val service: TicketOrderService,
    private val tokens: OrderTokenService,
) {
    @GetMapping("/public/orgs/{username}/events/{eventId}")
    fun sale(
        @PathVariable username: String,
        @PathVariable eventId: UUID,
    ): PublicSaleView = withOrganization(username) { tenant -> service.publicSale(tenant, eventId) }

    @PostMapping("/public/orgs/{username}/events/{eventId}/orders")
    @ResponseStatus(HttpStatus.CREATED)
    fun place(
        @PathVariable username: String,
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: PlaceOrderRequest,
    ): PlacedOrderView = withOrganization(username) { tenant -> service.place(tenant, eventId, request) }

    @GetMapping("/orders/{token}")
    fun view(
        @PathVariable token: String,
    ): OrderView = withOrder(token) { orderId -> service.view(orderId) }

    @PostMapping("/orders/{token}/declare")
    fun declare(
        @PathVariable token: String,
        @Valid @RequestBody request: DeclarePaymentRequest,
    ): OrderView = withOrder(token) { orderId -> service.declare(orderId, request.paymentReference) }

    private fun <T> withOrganization(
        username: String,
        block: (TenantInfo) -> T,
    ): T {
        val tenant =
            tenants.findByUsername(username)?.takeUnless { tenantAccessGate.isSuspended(it.id.toString()) }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This organization was not found")
        return TenantContext.withTenant(tenant.id.toString()) { block(tenant) }
    }

    private fun <T> withOrder(
        token: String,
        block: (UUID) -> T,
    ): T {
        val claims = parse(token)
        val tenantId =
            (claims[OrderTokenService.CLAIM_TENANT_ID] as? String)
                ?.takeIf { claims[OrderTokenService.CLAIM_TYPE] == OrderTokenService.TOKEN_TYPE }
                ?.takeUnless { tenantAccessGate.isSuspended(it) }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This order link is invalid")
        return TenantContext.withTenant(tenantId) { block(UUID.fromString(claims.subject)) }
    }

    private fun parse(token: String): Claims =
        try {
            tokens.parse(token)
        } catch (ex: RuntimeException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This order link is invalid", ex)
        }
}

/**
 * The organization's side of an event's sales (JIKU-177): the orders, and the
 * decision on a payment — confirmed (tickets issued) or refused with a reason.
 */
@RestController
@RequestMapping("/events/{eventId}/orders")
@PreAuthorize("hasRole('ORGANIZER')")
class TicketOrderController(
    private val service: TicketOrderService,
) {
    @GetMapping
    fun list(
        @PathVariable eventId: UUID,
        @RequestParam(required = false) status: TicketOrderStatus?,
    ): List<OrganizerOrderView> = service.list(eventId, status)

    @PostMapping("/{orderId}/confirm")
    fun confirm(
        @PathVariable eventId: UUID,
        @PathVariable orderId: UUID,
    ): OrganizerOrderView = service.confirm(eventId, orderId, currentUser())

    @PostMapping("/{orderId}/reject")
    fun reject(
        @PathVariable eventId: UUID,
        @PathVariable orderId: UUID,
        @Valid @RequestBody request: RejectOrderRequest,
    ): OrganizerOrderView = service.reject(eventId, orderId, request.reason, currentUser())

    private fun currentUser(): String? = SecurityContextHolder.getContext().authentication?.name
}

/**
 * Gives back the places of unpaid orders once their hold has passed (JIKU-177).
 * Tenant by tenant, like the other platform sweeps: the list comes from a
 * cross-tenant read, each expiry runs under the tenant of its orders.
 */
@Component
class TicketOrderExpiryJob(
    private val orders: TicketOrderRepository,
    private val service: TicketOrderService,
) {
    @Scheduled(cron = "\${sales.orders.expiry-cron:30 * * * * *}")
    @SchedulerLock(name = "TicketOrderExpiryJob.sweep")
    fun sweep() {
        val now = Instant.now()
        for (tenantId in orders.expiredTenantIds(now)) {
            TenantContext.withTenant(tenantId) { service.expireOverdue(now) }
        }
    }
}
