package com.jiku.shared

import java.time.Instant

/**
 * One step of an account deletion (JIKU-316), emailed by notification: asked
 * (with the date and how to cancel), cancelled by signing in again, done, and,
 * to the other members, their organization closed.
 */
data class AccountDeletionNotice(
    val kind: String,
    val email: String,
    val language: String,
    /** When the erasure happens; set for [KIND_REQUESTED]. */
    val scheduledFor: Instant? = null,
    /** The organizations deleted with the account, by name. */
    val organizations: List<String> = emptyList(),
    /** Where to sign in to cancel; set for [KIND_REQUESTED]. */
    val actionUrl: String? = null,
) {
    companion object {
        const val KIND_REQUESTED = "REQUESTED"
        const val KIND_CANCELLED = "CANCELLED"
        const val KIND_COMPLETED = "COMPLETED"
        const val KIND_ORGANIZATION_CLOSED = "ORGANIZATION_CLOSED"
    }
}
