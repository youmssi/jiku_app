package com.jiku.money.internal

import com.jiku.money.ManualPaymentInstructions
import com.jiku.shared.TenantContext
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/** A batch just opened, and the payment it waits for when it has one. */
data class OpenedBatchView(
    val batchId: UUID,
    val funding: CommissionFunding,
    val status: CommissionBatchStatus,
    val size: Int,
    val totalMinor: Long,
    val currency: String,
    /** The online payment to follow, when the batch is paid online. */
    val payment: PaymentInitiationResult?,
    /** What to transfer and the reference to quote, when the batch is paid by transfer. */
    val instructions: ManualPaymentInstructions? = null,
)

/** Opens a batch and, when it must be paid, starts its payment in the same transaction. */
@Service
class CommissionCheckout(
    private val commission: CommissionService,
    private val payments: PaymentService,
    private val manualPayments: ManualPaymentService,
) {
    @Transactional
    fun open(
        eventId: UUID,
        request: OpenBatchRequest,
    ): OpenedBatchView {
        val opened = commission.open(eventId, request)
        val due = opened.totalMinor > 0
        val instructions =
            if (due && request.manual) {
                manualPayments.requestCommission(opened.batch, opened.totalMinor).also { opened.batch.paymentId = it.paymentId }
            } else {
                null
            }
        val payment =
            if (due && !request.manual) {
                payments.checkoutCommission(opened.batch, opened.totalMinor).also { opened.batch.paymentId = it.paymentId }
            } else {
                null
            }
        val batch = opened.batch
        return OpenedBatchView(
            batchId = requireNotNull(batch.id),
            funding = batch.funding,
            status = batch.status,
            size = batch.size,
            totalMinor = opened.totalMinor,
            currency = batch.currency,
            payment = payment,
            instructions = instructions,
        )
    }
}

/**
 * The commission on an event's ticket sales (JIKU-178): where it stands per
 * category, the price of the next batch, and opening it. Managers only, like
 * every payment to Jikū.
 */
@RestController
@RequestMapping("/events/{eventId}/commission")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class CommissionController(
    private val commission: CommissionService,
    private val checkout: CommissionCheckout,
) {
    @GetMapping
    fun overview(
        @PathVariable eventId: UUID,
    ): CommissionOverview = commission.overview(eventId)

    @GetMapping("/quote")
    fun quote(
        @PathVariable eventId: UUID,
        @RequestParam ticketTypeId: UUID,
    ): CommissionQuote = commission.quote(eventId, ticketTypeId)

    @PostMapping("/batches")
    fun open(
        @PathVariable eventId: UUID,
        @RequestBody request: OpenBatchRequest,
    ): OpenedBatchView = checkout.open(eventId, request)
}

/**
 * Closes the batches of events that are over (JIKU-178): the unused part of a
 * paid batch becomes a credit, a credit batch owes what it used. Tenant by
 * tenant, like the other sweeps.
 */
@Component
class CommissionCloseJob(
    private val batches: CommissionBatchRepository,
    private val commission: CommissionService,
) {
    @Scheduled(cron = "\${sales.commission.close-cron:0 15 * * * *}")
    @SchedulerLock(name = "CommissionCloseJob.sweep")
    fun sweep() {
        val now = Instant.now()
        for (tenantId in batches.tenantsDueToClose(now)) {
            TenantContext.withTenant(tenantId) { commission.closeDue(now) }
        }
    }
}
