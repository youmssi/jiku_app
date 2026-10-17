package com.jiku.tenant.internal

import com.jiku.shared.SenderGate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** The tenant module's answer to [SenderGate]: the person's verified-email flag. */
@Component
@Transactional(readOnly = true)
class SenderGateAdapter(
    private val users: OrganizerUserRepository,
) : SenderGate {
    override fun hasVerifiedEmail(userId: String): Boolean {
        val id = runCatching { UUID.fromString(userId) }.getOrNull() ?: return false
        return users.findById(id).map { it.emailVerified }.orElse(false)
    }
}
