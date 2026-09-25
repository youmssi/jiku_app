package com.jiku.shared

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/**
 * Runs an event listener's work for a tenant the event names. Hibernate fixes
 * a session's tenant when its transaction opens, so the tenant is bound first:
 * the caller's transaction is joined only when it already belongs to that
 * tenant; otherwise a new one opens under it, and rows never land on the
 * unresolved tenant.
 */
@Component
class TenantTransaction(
    transactionManager: PlatformTransactionManager,
) {
    private val joining = TransactionTemplate(transactionManager)
    private val separate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    fun run(
        tenantId: String,
        block: () -> Unit,
    ) {
        val previous = TenantContext.get()
        val joinable = previous == tenantId || !TransactionSynchronizationManager.isActualTransactionActive()
        TenantContext.set(tenantId)
        try {
            (if (joinable) joining else separate).executeWithoutResult { block() }
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }
}
