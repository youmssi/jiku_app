package com.jiku.money.internal

import com.jiku.shared.TenantContext
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Asks the providers about the online payments whose callback has not arrived
 * (JIKU-272), platform-wide, and settles each under its own tenant like a
 * callback would. Only adapters that answer a lookup are swept; the manual
 * transfer, the sandbox and CinetPay are left alone.
 */
@Component
class PaymentReconciliationJob(
    private val payments: PaymentRepository,
    private val paymentService: PaymentService,
    private val properties: PaymentProperties,
) {
    @Scheduled(
        fixedDelayString = "\${billing.payment.reconcile-delay:PT2M}",
        initialDelayString = "\${billing.payment.reconcile-delay:PT2M}",
    )
    @SchedulerLock(name = "PaymentReconciliationJob.sweep")
    fun sweep() {
        val now = Instant.now()
        val abandonBefore = now.minus(properties.abandonAfter)
        payments
            .findPendingToReconcile(
                providers = RECONCILED_PROVIDERS,
                before = now.minus(properties.reconcileAfter),
                after = abandonBefore.minus(LOOKBACK_MARGIN),
                limit = BATCH,
            ).forEach { row ->
                val paymentId = row[0] as? UUID ?: UUID.fromString(row[0].toString())
                val tenantId = row[1].toString()
                TenantContext.withTenant(tenantId) { paymentService.reconcile(paymentId, abandonBefore) }
            }
    }

    private companion object {
        val RECONCILED_PROVIDERS = listOf(DexPayPaymentProvider.NAME)
        const val BATCH = 200

        /** Older payments were abandoned by an earlier sweep; this margin covers a few missed runs. */
        val LOOKBACK_MARGIN: Duration = Duration.ofDays(1)
    }
}
