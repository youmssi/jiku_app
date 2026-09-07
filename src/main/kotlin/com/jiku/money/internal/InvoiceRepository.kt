package com.jiku.money.internal

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface InvoiceRepository : JpaRepository<Invoice, UUID> {
    fun findAllByOrderByIssuedAtDesc(): List<Invoice>

    /**
     * La liste des factures avec leurs lignes en une passe (anti-N+1). Les lignes
     * restent EAGER sur l'entité car le détail et le PDF les lisent hors
     * transaction ; ce join-fetch évite la requête supplémentaire par document.
     */
    @Query("select distinct i from Invoice i left join fetch i.lines order by i.issuedAt desc")
    fun findAllWithLinesOrderByIssuedAtDesc(): List<Invoice>

    fun findByPaymentId(paymentId: UUID): Invoice?

    fun existsByCorrectedInvoiceId(correctedInvoiceId: UUID): Boolean
}

interface InvoiceNumberCounterRepository : JpaRepository<InvoiceNumberCounter, UUID> {
    /**
     * Takes a write lock on the tenant's counter for the year, held until the
     * issuing transaction commits. Concurrent issuers therefore queue rather than
     * both reading the same number, and a rollback releases the lock with the
     * number unspent.
     *
     * Stays a derived query rather than native SQL so Hibernate's `@TenantId`
     * predicate is applied — a native lookup here would find another tenant's
     * counter and hand out their next number.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findByFiscalYear(fiscalYear: Int): InvoiceNumberCounter?

    /**
     * Unlocked existence check, used only when creating the row for a new fiscal
     * year. Taking the write lock here would serialise every issuer behind the
     * creation path rather than behind the increment. HQL, not native SQL, so the
     * tenant predicate is still applied.
     */
    @Query("select c from InvoiceNumberCounter c where c.fiscalYear = :fiscalYear")
    fun findExisting(
        @Param("fiscalYear") fiscalYear: Int,
    ): InvoiceNumberCounter?
}
