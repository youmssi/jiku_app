package com.jiku.support

import com.jiku.shared.AccountNotice
import com.jiku.tenant.internal.OrganizerUserRepository
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Sending waits for a proven address (JIKU-253). Everyday integration tests
 * sign up on `@test.example` and act as organizers who already confirmed
 * their email, as a real organizer does before sending; tests about the
 * verification itself sign up on `@jiku.test` and walk the real path.
 */
@Component
class VerifiedTestSenders(
    private val users: OrganizerUserRepository,
) {
    @EventListener
    fun onSignup(notice: AccountNotice) {
        if (notice.kind != AccountNotice.KIND_EMAIL_VERIFICATION || !notice.email.endsWith("@test.example")) return
        users.findByEmail(notice.email)?.let {
            it.emailVerified = true
            users.save(it)
        }
    }
}
