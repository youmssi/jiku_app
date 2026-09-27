package com.jiku.tenant.internal

import com.jiku.shared.BaseTenantEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

/** Personal: an identity document and a confirmed phone. Company: the business's registration papers. */
enum class VerificationKind {
    PERSONAL,
    COMPANY,
}

enum class VerificationStatus {
    PENDING,
    APPROVED,
    REJECTED,
}

/**
 * One request to verify an organization (JIKU-175, référentiel métier §9), and
 * the Jikū team's decision on it. The documents live in the private store; this
 * row keeps what the decision rests on, and outlives the documents.
 */
@Entity
@Table(name = "organization_verification")
class OrganizationVerification(
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, updatable = false, length = 16)
    val kind: VerificationKind,
    @Column(name = "legal_name", nullable = false, updatable = false, length = 200)
    val legalName: String,
    @Column(name = "document_type", nullable = false, updatable = false, length = 40)
    val documentType: String,
    @Column(name = "registration_number", updatable = false, length = 80)
    val registrationNumber: String? = null,
    @Column(name = "tax_identifier", updatable = false, length = 80)
    val taxIdentifier: String? = null,
    @Column(name = "phone", updatable = false, length = 32)
    val phone: String? = null,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: VerificationStatus = VerificationStatus.PENDING

    @Column(name = "submitted_at", nullable = false, updatable = false)
    val submittedAt: Instant = Instant.now()

    @Column(name = "decided_at")
    var decidedAt: Instant? = null

    @Column(name = "decided_by")
    var decidedBy: UUID? = null

    @Column(name = "rejection_reason", length = 500)
    var rejectionReason: String? = null

    @Column(name = "documents_purged_at")
    var documentsPurgedAt: Instant? = null
}

/** One uploaded file of a request, addressed by its key in the private store. */
@Entity
@Table(name = "verification_document")
class VerificationDocument(
    @Column(name = "verification_id", nullable = false, updatable = false)
    val verificationId: UUID,
    @Column(name = "position", nullable = false, updatable = false)
    val position: Int,
    @Column(name = "object_key", nullable = false, updatable = false, length = 300)
    val objectKey: String,
    @Column(name = "content_type", nullable = false, updatable = false, length = 80)
    val contentType: String,
    @Column(name = "size_bytes", nullable = false, updatable = false)
    val sizeBytes: Int,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}

/**
 * The organization's phone confirmation: the latest code sent (hashed), how many
 * wrong guesses it took, and how many codes went out in the current window.
 */
@Entity
@Table(name = "phone_verification")
class PhoneVerification(
    @Column(name = "phone", nullable = false, length = 32)
    var phone: String,
    @Column(name = "code_hash", nullable = false, length = 128)
    var codeHash: String,
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,
    @Column(name = "window_start", nullable = false)
    var windowStart: Instant,
) : BaseTenantEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0

    @Column(name = "sent_count", nullable = false)
    var sentCount: Int = 0

    @Column(name = "verified_at")
    var verifiedAt: Instant? = null
}

interface OrganizationVerificationRepository : JpaRepository<OrganizationVerification, UUID> {
    fun findByKindOrderBySubmittedAtDesc(kind: VerificationKind): List<OrganizationVerification>

    /** Tenant owning a request — admin requests carry no tenant, so this read is native and cross-tenant. */
    @Query(value = "SELECT tenant_id FROM organization_verification WHERE id = :id", nativeQuery = true)
    fun findTenantIdById(
        @Param("id") id: UUID,
    ): String?

    /** Requests of every tenant in [status], oldest first: the back-office review queue. */
    @Query(
        value =
            "SELECT id, tenant_id AS tenantId, kind, status, legal_name AS legalName, document_type AS documentType, " +
                "registration_number AS registrationNumber, tax_identifier AS taxIdentifier, phone, " +
                "submitted_at AS submittedAt, decided_at AS decidedAt, rejection_reason AS rejectionReason, " +
                "documents_purged_at AS documentsPurgedAt " +
                "FROM organization_verification WHERE status = :status ORDER BY submitted_at ASC LIMIT :limit",
        nativeQuery = true,
    )
    fun adminFindByStatus(
        @Param("status") status: String,
        @Param("limit") limit: Int,
    ): List<AdminVerificationRow>

    /** Approved requests whose documents are older than [before] and still stored: the purge's work list. */
    @Query(
        value =
            "SELECT id FROM organization_verification WHERE status = 'APPROVED' AND documents_purged_at IS NULL " +
                "AND decided_at < :before",
        nativeQuery = true,
    )
    fun findApprovedToPurge(
        @Param("before") before: Instant,
    ): List<UUID>
}

/** Projection of one request for the back-office queue. */
interface AdminVerificationRow {
    val id: UUID
    val tenantId: String
    val kind: String
    val status: String
    val legalName: String
    val documentType: String
    val registrationNumber: String?
    val taxIdentifier: String?
    val phone: String?
    val submittedAt: Instant
    val decidedAt: Instant?
    val rejectionReason: String?
    val documentsPurgedAt: Instant?
}

interface VerificationDocumentRepository : JpaRepository<VerificationDocument, UUID> {
    fun findByVerificationIdOrderByPositionAsc(verificationId: UUID): List<VerificationDocument>
}

interface PhoneVerificationRepository : JpaRepository<PhoneVerification, UUID> {
    @Query("SELECT p FROM PhoneVerification p")
    fun findCurrent(): List<PhoneVerification>
}
