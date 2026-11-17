package com.jiku.shared

import java.time.Instant

/**
 * A change to how an account signs in (JIKU-317): the second factor turned on or
 * off, new backup codes, or a sign-in from a device not seen before. Published by
 * the module that owns the account, emailed by the messaging module, so the
 * holder learns of a change they did not make.
 */
data class SecurityNotice(
    val kind: String,
    val email: String,
    val language: String = MessageLanguage.FRENCH,
    /** What the browser says it is, for a new-device sign-in. */
    val device: String? = null,
    val at: Instant = Instant.now(),
    /** Where to review the account's security. */
    val actionUrl: String? = null,
) {
    companion object {
        const val KIND_MFA_ENABLED = "MFA_ENABLED"
        const val KIND_MFA_DISABLED = "MFA_DISABLED"
        const val KIND_BACKUP_CODES_RENEWED = "BACKUP_CODES_RENEWED"
        const val KIND_NEW_DEVICE = "NEW_DEVICE"
        const val KIND_PASSKEY_ADDED = "PASSKEY_ADDED"
    }
}
