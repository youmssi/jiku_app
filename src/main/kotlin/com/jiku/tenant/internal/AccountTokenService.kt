package com.jiku.tenant.internal

import com.jiku.shared.AccountNotice
import com.jiku.shared.MessageLanguage
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Password reset and email verification (JIKU-49): single-use tokens mailed as
 * links, stored hashed. Issuing a token replaces any outstanding one of the same
 * purpose, and consuming one is strictly one-shot.
 */
@Service
class AccountTokenService(
    private val users: OrganizerUserRepository,
    private val tokens: AccountTokenRepository,
    private val passwordEncoder: PasswordEncoder,
    private val properties: AccountProperties,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Always succeeds from the caller's point of view — whether the address has
     * an account must not be observable (no account enumeration).
     */
    @Transactional
    fun requestPasswordReset(email: String) {
        val user = users.findByEmail(email) ?: return
        val raw = issue(user, AccountTokenPurpose.PASSWORD_RESET, properties.passwordResetTtl)
        events.publishEvent(
            AccountNotice(
                kind = AccountNotice.KIND_PASSWORD_RESET,
                email = user.email,
                actionUrl = localizedLink("/reset-password?token=$raw"),
                language = requestLanguage(),
            ),
        )
    }

    @Transactional
    fun resetPassword(
        rawToken: String,
        newPassword: String,
    ) {
        val token = consume(rawToken, AccountTokenPurpose.PASSWORD_RESET)
        val user = users.findById(token.userId).orElseThrow { invalidToken() }
        user.passwordHash = requireNotNull(passwordEncoder.encode(newPassword))
        // Refresh tokens issued before this instant are rejected from now on, so a
        // reset cuts off whoever had the old credentials.
        user.passwordChangedAt = Instant.now()
        users.save(user)
    }

    /** No-op for already-verified accounts; issuing replaces any pending token. */
    @Transactional
    fun sendEmailVerification(user: OrganizerUser) {
        if (user.emailVerified) return
        val raw = issue(user, AccountTokenPurpose.EMAIL_VERIFICATION, properties.emailVerificationTtl)
        events.publishEvent(
            AccountNotice(
                kind = AccountNotice.KIND_EMAIL_VERIFICATION,
                email = user.email,
                actionUrl = localizedLink("/verify-email?token=$raw"),
                language = requestLanguage(),
            ),
        )
    }

    @Transactional
    fun resendEmailVerification(userId: String) {
        val user =
            users.findById(UUID.fromString(userId)).orElseThrow {
                ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user")
            }
        sendEmailVerification(user)
    }

    @Transactional
    fun verifyEmail(rawToken: String) {
        val token = consume(rawToken, AccountTokenPurpose.EMAIL_VERIFICATION)
        val user = users.findById(token.userId).orElseThrow { invalidToken() }
        user.emailVerified = true
        users.save(user)
    }

    /** The language of the browser that asked for this email; French when it says nothing usable. */
    private fun requestLanguage(): String = MessageLanguage.normalize(LocaleContextHolder.getLocale().toLanguageTag())

    /** French is the web app's unprefixed default; every other language lives under its own prefix. */
    private fun localizedLink(path: String): String {
        val language = requestLanguage()
        val prefix = if (language == MessageLanguage.FRENCH) "" else "/$language"
        return "${properties.appBaseUrl}$prefix$path"
    }

    private fun issue(
        user: OrganizerUser,
        purpose: AccountTokenPurpose,
        ttl: Duration,
    ): String {
        tokens.deleteByUserIdAndPurposeAndUsedAtIsNull(requireNotNull(user.id), purpose)
        val raw = Tokens.generate()
        tokens.saveAndFlush(
            AccountToken(
                userId = requireNotNull(user.id),
                purpose = purpose,
                tokenHash = Tokens.hash(raw),
                expiresAt = Instant.now().plus(ttl),
            ),
        )
        return raw
    }

    private fun consume(
        raw: String,
        purpose: AccountTokenPurpose,
    ): AccountToken {
        val token = tokens.findByTokenHash(Tokens.hash(raw)) ?: throw invalidToken()
        if (token.purpose != purpose || token.usedAt != null || token.expiresAt.isBefore(Instant.now())) {
            throw invalidToken()
        }
        token.usedAt = Instant.now()
        return tokens.save(token)
    }

    // One message for every failure mode: which mode it was must not be observable.
    private fun invalidToken() = ResponseStatusException(HttpStatus.BAD_REQUEST, "This link is invalid or has expired")
}
