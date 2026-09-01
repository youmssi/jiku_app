package com.jiku.tenant

import java.time.Instant
import java.util.UUID

/**
 * Read-only view of a tenant, safe to share across module boundaries.
 */
data class TenantInfo(
    val id: UUID,
    val name: String,
    val contactEmail: String,
    val status: String,
    val createdAt: Instant,
    val displayName: String,
    val logoUrl: String?,
    val primaryColor: String,
    /**
     * Present only once the organization has supplied it. The billing module reads
     * this to stamp a buyer onto an invoice (JIKU-69); it is null for the many
     * tenants who never need one.
     */
    val legalIdentity: TenantLegalIdentityInfo? = null,
)

/**
 * The organization's legal identity as it must appear on an invoice, shared across
 * the module boundary. Snapshotted onto each invoice at issue time so a later
 * correction never rewrites a document already sent to an accounts department.
 */
data class TenantLegalIdentityInfo(
    val legalName: String,
    val registrationNumber: String?,
    val taxIdentifier: String?,
    val addressLine: String,
    val city: String,
    /** ISO 3166-1 alpha-2; selects the tax treatment applied to the invoice. */
    val country: String,
)
