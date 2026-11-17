package com.jiku.tenant.internal

import com.jiku.mfa.MfaEnrollmentStart
import com.jiku.mfa.MfaModuleApi
import com.jiku.mfa.MfaStatus
import com.jiku.mfa.MfaSubject
import com.jiku.shared.MessageLanguage
import com.jiku.shared.SecurityNotice
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class MfaCodeRequest(
    @field:NotBlank @field:Size(max = 12) val code: String,
)

data class MfaDisableRequest(
    @field:Size(max = 12) val code: String? = null,
    @field:Size(max = 32) val backupCode: String? = null,
)

/** Backup codes, shown once: only their hashes are kept. */
data class BackupCodesView(
    val backupCodes: List<String>,
)

/**
 * Settings → Security (JIKU-317): the account's second factor and its signed-in
 * sessions. Every change to the factor is emailed to the account's address.
 */
@RestController
@RequestMapping("/auth/me")
@PreAuthorize("hasRole('USER')")
class AccountSecurityController(
    private val security: AccountSecurityService,
    private val sessions: OrganizerSessionService,
) {
    @GetMapping("/mfa")
    fun mfa(authentication: Authentication): MfaStatus = security.status(userId(authentication))

    @PostMapping("/mfa/setup")
    fun setup(authentication: Authentication): MfaEnrollmentStart = security.start(userId(authentication))

    @PostMapping("/mfa/confirm")
    fun confirm(
        authentication: Authentication,
        @Valid @RequestBody request: MfaCodeRequest,
    ): BackupCodesView = security.confirm(userId(authentication), request.code)

    @PostMapping("/mfa/disable")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun disable(
        authentication: Authentication,
        @Valid @RequestBody request: MfaDisableRequest,
    ) = security.disable(userId(authentication), request)

    @PostMapping("/mfa/backup-codes")
    fun renewBackupCodes(
        authentication: Authentication,
        @Valid @RequestBody request: MfaCodeRequest,
    ): BackupCodesView = security.renewBackupCodes(userId(authentication), request.code)

    @GetMapping("/sessions")
    fun sessions(authentication: Authentication): List<SessionView> = sessions.list(userId(authentication), authentication.sessionId())

    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun close(
        authentication: Authentication,
        @PathVariable id: UUID,
    ) = sessions.close(userId(authentication), id)

    /** "Sign out everywhere else": every session but this one. */
    @PostMapping("/sessions/close-others")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun closeOthers(authentication: Authentication) =
        sessions.closeAll(userId(authentication), authentication.sessionId()?.let { runCatching { UUID.fromString(it) }.getOrNull() })

    private fun userId(authentication: Authentication): UUID = UUID.fromString(authentication.name)
}

@Service
class AccountSecurityService(
    private val users: OrganizerUserRepository,
    private val mfa: MfaModuleApi,
    private val events: ApplicationEventPublisher,
    private val accountProperties: AccountProperties,
) {
    fun status(userId: UUID): MfaStatus = mfa.status(subject(userId))

    @Transactional
    fun start(userId: UUID): MfaEnrollmentStart = mfa.startEnrollment(subject(userId), requireUser(userId).email)

    @Transactional
    fun confirm(
        userId: UUID,
        code: String,
    ): BackupCodesView {
        val codes = mfa.confirmEnrollment(subject(userId), code.trim())
        notify(userId, SecurityNotice.KIND_MFA_ENABLED)
        return BackupCodesView(codes)
    }

    @Transactional
    fun disable(
        userId: UUID,
        request: MfaDisableRequest,
    ) {
        mfa.disable(subject(userId), request.code?.trim(), request.backupCode)
        notify(userId, SecurityNotice.KIND_MFA_DISABLED)
    }

    @Transactional
    fun renewBackupCodes(
        userId: UUID,
        code: String,
    ): BackupCodesView {
        val codes = mfa.renewBackupCodes(subject(userId), code)
        notify(userId, SecurityNotice.KIND_BACKUP_CODES_RENEWED)
        return BackupCodesView(codes)
    }

    private fun notify(
        userId: UUID,
        kind: String,
    ) {
        val language = MessageLanguage.normalize(LocaleContextHolder.getLocale().toLanguageTag())
        val prefix = if (language == MessageLanguage.FRENCH) "" else "/$language"
        events.publishEvent(
            SecurityNotice(
                kind = kind,
                email = requireUser(userId).email,
                language = language,
                actionUrl = "${accountProperties.appBaseUrl}$prefix/settings?tab=security",
            ),
        )
    }

    private fun subject(userId: UUID) = MfaSubject(MfaSubject.ORGANIZER, userId)

    private fun requireUser(userId: UUID): OrganizerUser =
        users.findById(userId).orElseThrow { ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unknown user") }
}
