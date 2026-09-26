package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
import com.jiku.tenant.AdminVerificationView
import com.jiku.tenant.VerificationDocumentLink
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * The Jikū team's side of verification (JIKU-175): the review queue across every
 * organization, short-lived links to the documents, and the decision. Admin
 * requests carry no tenant, so each action binds the request's tenant before its
 * transaction opens, like the other platform-admin actions.
 *
 * A rejection deletes the documents at once; an approval keeps them for
 * [VerificationProperties.documentRetention], then [VerificationDocumentPurgeJob]
 * deletes them. Only the decision outlives them.
 */
@Service
class VerificationAdminService(
    private val verifications: OrganizationVerificationRepository,
    private val documents: VerificationDocumentRepository,
    private val tenants: TenantRepository,
    private val store: DocumentStore,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    fun list(
        status: VerificationStatus,
        limit: Int,
    ): List<AdminVerificationView> {
        val rows = transactions.execute { verifications.adminFindByStatus(status.name, limit.coerceIn(1, MAX_PAGE)) }.orEmpty()
        val names =
            transactions
                .execute { tenants.findAllById(rows.map { UUID.fromString(it.tenantId) }.toSet()) }
                .orEmpty()
                .associate { it.id.toString() to it.name }
        return rows.map { it.toView(names[it.tenantId]) }
    }

    /** Links that let the reviewer open each document for a few minutes; empty once the documents are deleted. */
    fun documentLinks(id: UUID): List<VerificationDocumentLink> =
        asTenantOf(id) {
            val request = verifications.findById(id).orElse(null) ?: notFound()
            if (request.documentsPurgedAt != null) {
                emptyList()
            } else {
                documents.findByVerificationIdOrderByPositionAsc(id).map {
                    VerificationDocumentLink(position = it.position, contentType = it.contentType, url = store.temporaryLink(it.objectKey))
                }
            }
        }

    fun decide(
        id: UUID,
        approve: Boolean,
        reason: String?,
        adminId: UUID?,
        now: Instant = Instant.now(),
    ): AdminVerificationView =
        asTenantOf(id) {
            val request = verifications.findById(id).orElse(null) ?: notFound()
            if (request.status != VerificationStatus.PENDING) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "This request was already decided")
            }
            if (!approve && reason.isNullOrBlank()) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the organization a reason for the refusal")
            }
            request.status = if (approve) VerificationStatus.APPROVED else VerificationStatus.REJECTED
            request.decidedAt = now
            request.decidedBy = adminId
            request.rejectionReason = if (approve) null else reason?.trim()?.take(500)
            if (!approve) purgeDocuments(request, now)
            verifications.save(request)
            val tenantName = tenants.findById(UUID.fromString(requireNotNull(request.tenantId))).orElse(null)?.name
            request.toAdminView(tenantName)
        }

    /** Deletes the stored documents of approved requests older than the retention period. */
    fun purgeExpired(
        before: Instant,
        now: Instant = Instant.now(),
    ): Int {
        val ids = transactions.execute { verifications.findApprovedToPurge(before) }.orEmpty()
        ids.forEach { id ->
            asTenantOf(id) {
                verifications.findById(id).orElse(null)?.let {
                    purgeDocuments(it, now)
                    verifications.save(it)
                }
            }
        }
        return ids.size
    }

    private fun purgeDocuments(
        request: OrganizationVerification,
        now: Instant,
    ) {
        val id = requireNotNull(request.id)
        val files = documents.findByVerificationIdOrderByPositionAsc(id)
        files.forEach { store.delete(it.objectKey) }
        documents.deleteAll(files)
        request.documentsPurgedAt = now
    }

    private fun <T> asTenantOf(
        id: UUID,
        block: () -> T,
    ): T {
        val tenantId = transactions.execute { verifications.findTenantIdById(id) } ?: notFound()
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            @Suppress("UNCHECKED_CAST")
            return transactions.execute { block() } as T
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun notFound(): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, "Verification request not found")

    private companion object {
        const val MAX_PAGE = 200
    }
}

/** Daily deletion of the documents of approved requests past their retention period. */
@Component
class VerificationDocumentPurgeJob(
    private val admin: VerificationAdminService,
    private val properties: VerificationProperties,
) {
    @Scheduled(cron = "\${verification.purge-cron:0 30 3 * * *}")
    fun purge() {
        val now = Instant.now()
        admin.purgeExpired(before = now.minus(properties.documentRetention), now = now)
    }
}

private fun AdminVerificationRow.toView(tenantName: String?): AdminVerificationView =
    AdminVerificationView(
        id = id,
        tenantId = tenantId,
        tenantName = tenantName,
        kind = kind,
        status = status,
        legalName = legalName,
        documentType = documentType,
        registrationNumber = registrationNumber,
        taxIdentifier = taxIdentifier,
        phone = phone,
        submittedAt = submittedAt,
        decidedAt = decidedAt,
        rejectionReason = rejectionReason,
        documentsAvailable = documentsPurgedAt == null,
    )

private fun OrganizationVerification.toAdminView(tenantName: String?): AdminVerificationView =
    AdminVerificationView(
        id = requireNotNull(id),
        tenantId = requireNotNull(tenantId),
        tenantName = tenantName,
        kind = kind.name,
        status = status.name,
        legalName = legalName,
        documentType = documentType,
        registrationNumber = registrationNumber,
        taxIdentifier = taxIdentifier,
        phone = phone,
        submittedAt = submittedAt,
        decidedAt = decidedAt,
        rejectionReason = rejectionReason,
        documentsAvailable = documentsPurgedAt == null,
    )
