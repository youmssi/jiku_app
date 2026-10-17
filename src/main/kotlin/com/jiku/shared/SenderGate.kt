package com.jiku.shared

import org.springframework.http.HttpStatus

/**
 * Whether a person has proven their email address, checked before the first
 * message the platform sends on an organization's behalf (JIKU-253). An
 * organization is created and prepared freely; sending waits for a proven
 * address. Exposed in `shared` so any module can check it without depending on
 * the tenant module, which provides the implementation.
 */
interface SenderGate {
    fun hasVerifiedEmail(userId: String): Boolean

    /** Refuses a send by a person whose address is not proven yet. */
    fun requireVerifiedSender(userId: String) {
        if (!hasVerifiedEmail(userId)) throw UnverifiedSenderException()
    }
}

/** A send was refused because the sender has not confirmed their email address. */
class UnverifiedSenderException :
    CodedRefusal(
        HttpStatus.FORBIDDEN,
        CodedRefusal.EMAIL_UNVERIFIED,
        "Confirm your email address before sending on behalf of your organization",
    )
