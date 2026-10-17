package com.jiku.shared

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * A refusal the caller is expected to act on (JIKU-254). Besides the status and
 * the human-readable detail, the problem body carries a stable [code] and the
 * position that explains it ([properties], e.g. the remaining allowance), so a
 * client shows the exact cause and the action that fixes it instead of
 * guessing from a status shared by several causes.
 */
open class CodedRefusal(
    status: HttpStatus,
    val code: String,
    reason: String,
    properties: Map<String, Any?> = emptyMap(),
) : ResponseStatusException(status, reason) {
    init {
        body.setProperty("code", code)
        properties.forEach { (name, value) -> body.setProperty(name, value) }
    }

    companion object {
        /** An event's guest allowance would be exceeded. */
        const val GUEST_ALLOWANCE = "billing.guest-allowance"

        /** The organization's services subscription ended: new bookings and walk-ins are paused. */
        const val SERVICES_PAUSED = "billing.services-paused"

        /** The sender's email address is not confirmed yet. */
        const val EMAIL_UNVERIFIED = "account.email-unverified"

        /** The organization is suspended by the platform. */
        const val TENANT_SUSPENDED = "account.suspended"

        /** A client in the day line has not paid yet. */
        const val CLIENT_UNPAID = "client.unpaid"
    }
}
