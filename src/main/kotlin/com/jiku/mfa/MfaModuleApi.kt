package com.jiku.mfa

import java.time.Instant
import java.util.UUID

/**
 * A second sign-in factor by authentication app (JIKU-317), for organizer
 * accounts and desk administrators alike. The module owning the account decides
 * when a code is asked; this one keeps the secrets, checks the codes, and signs
 * the short-lived tokens that carry a sign-in between its two steps.
 *
 * Refusals are [com.jiku.shared.CodedRefusal]s: `auth.mfa-invalid` (401) for a
 * wrong, replayed or expired code or step token, and 429 when a subject runs out
 * of attempts.
 */
interface MfaModuleApi {
    /** Whether desk administrators must have the factor before they may sign in (`ADMIN_MFA_REQUIRED`). */
    fun adminRequired(): Boolean

    fun status(subject: MfaSubject): MfaStatus

    /** Starts (or restarts) setting up the app; nothing is enforced until [confirmEnrollment]. */
    fun startEnrollment(
        subject: MfaSubject,
        accountLabel: String,
    ): MfaEnrollmentStart

    /** Turns the factor on with a first code from the app; returns the backup codes, shown once. */
    fun confirmEnrollment(
        subject: MfaSubject,
        code: String,
    ): List<String>

    /** Checks an app code or a single-use backup code; exactly one of the two is given. */
    fun verify(
        subject: MfaSubject,
        code: String?,
        backupCode: String?,
    )

    /** Turns the factor off after a valid code. */
    fun disable(
        subject: MfaSubject,
        code: String?,
        backupCode: String?,
    )

    /** Replaces the backup codes after a valid code. */
    fun renewBackupCodes(
        subject: MfaSubject,
        code: String,
    ): List<String>

    /** Removes everything kept for [subject], when its account is erased. */
    fun forget(subject: MfaSubject)

    /** A token that opens only the code step of a sign-in, for [purpose]. */
    fun challenge(
        subject: MfaSubject,
        purpose: MfaPurpose,
    ): String

    /** The subject a step token was issued for, or an `auth.mfa-invalid` refusal. */
    fun readChallenge(
        token: String,
        kind: String,
        purpose: MfaPurpose,
    ): UUID

    /** Remembers this device: no code is asked on it while the token is valid. */
    fun trustDevice(subject: MfaSubject): String

    /** Whether [token] was issued for [subject] since its factor was last turned on. */
    fun isTrustedDevice(
        subject: MfaSubject,
        token: String?,
    ): Boolean
}

/** Whose factor: an organizer account or a desk administrator, by id. */
data class MfaSubject(
    val kind: String,
    val id: UUID,
) {
    companion object {
        const val ORGANIZER = "ORGANIZER"
        const val ADMIN = "ADMIN"
    }
}

/** What a step token lets its holder do next. */
enum class MfaPurpose {
    /** Enter the code of a factor already on. */
    VERIFY,

    /** Set up a factor the account must have before it may sign in. */
    SETUP,
}

data class MfaStatus(
    val enabled: Boolean,
    val enabledAt: Instant?,
    val backupCodesLeft: Int,
)

/** What the authentication app needs: the key to type and the link a QR code encodes. */
data class MfaEnrollmentStart(
    val secret: String,
    val otpauthUri: String,
)
