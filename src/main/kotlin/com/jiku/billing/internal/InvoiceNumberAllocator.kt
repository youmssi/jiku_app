package com.jiku.billing.internal

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Creates a tenant's counter row for a fiscal year if it does not exist yet
 * (JIKU-69).
 *
 * Runs in its own transaction for a reason that is easy to get wrong twice over.
 * In PostgreSQL a failed statement aborts the whole transaction, so letting a
 * losing race fail inside the issuing transaction would leave it unusable — and
 * that transaction still has an invoice to write. `REQUIRES_NEW` confines the
 * abort to a transaction we are willing to throw away.
 *
 * The unique-violation is deliberately **not** caught here: catching it inside
 * this method would leave the transaction marked rollback-only and Spring would
 * then fail the commit anyway. The caller catches it instead, outside this
 * boundary, where the failed transaction has already been rolled back cleanly.
 *
 * A losing racer blocks on the unique index until the winner commits, so by the
 * time this returns or throws, the row exists.
 */
@Component
class InvoiceNumberAllocator(
    private val counters: InvoiceNumberCounterRepository,
) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun ensureCounterExists(fiscalYear: Int) {
        if (counters.findExisting(fiscalYear) != null) {
            return
        }
        counters.saveAndFlush(InvoiceNumberCounter(fiscalYear = fiscalYear, nextNumber = 1))
    }
}
