package com.jiku.shared

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Whether the current organization is verified (référentiel métier §9): a
 * personal or a company verification approved by the Jikū team. Every step that
 * makes a client pay the organization requires it. Exposed in `shared` so any
 * module can check it without depending on the tenant module, which provides
 * the implementation.
 */
interface VerificationGate {
    /**
     * The current organization's approved verification: `COMPANY`, `PERSONAL`,
     * or null when none is approved. A company verification wins over a personal one.
     */
    fun verifiedKind(): String?

    fun isVerified(): Boolean = verifiedKind() != null

    /** Refuses a step that makes clients pay while the organization is not verified. */
    fun requireVerified() {
        if (!isVerified()) throw VerificationRequiredException()
    }
}

/** A step that makes clients pay was refused because the organization is not verified. */
class VerificationRequiredException :
    ResponseStatusException(HttpStatus.FORBIDDEN, "Verify your organization before asking clients to pay")
