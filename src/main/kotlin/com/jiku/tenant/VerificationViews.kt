package com.jiku.tenant

import java.time.Instant
import java.util.UUID

/** One verification request as the back-office review queue shows it (JIKU-175). */
data class AdminVerificationView(
    val id: UUID,
    val tenantId: String,
    val tenantName: String?,
    /** PERSONAL or COMPANY. */
    val kind: String,
    /** PENDING, APPROVED or REJECTED. */
    val status: String,
    val legalName: String,
    val documentType: String,
    val registrationNumber: String?,
    val taxIdentifier: String?,
    val phone: String?,
    val submittedAt: Instant,
    val decidedAt: Instant?,
    val rejectionReason: String?,
    /** False once the documents have been deleted (after rejection, or past the retention period). */
    val documentsAvailable: Boolean,
)

/** A link that lets a reviewer open one document for a few minutes. */
data class VerificationDocumentLink(
    val position: Int,
    val contentType: String,
    val url: String,
)
