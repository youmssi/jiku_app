package com.jiku.shared

/**
 * An organization is being erased at the end of its owner's account deletion
 * (JIKU-316). Published inside the closing transaction, with the organization
 * bound as the current tenant: each module erases the personal data it holds
 * for it, synchronously, so a failure leaves the organization to be retried.
 */
data class OrganizationClosed(
    val tenantId: String,
)
