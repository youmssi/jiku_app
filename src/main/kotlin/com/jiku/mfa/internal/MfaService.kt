package com.jiku.mfa.internal

import com.jiku.mfa.MfaEnrollmentStart
import com.jiku.mfa.MfaModuleApi
import com.jiku.mfa.MfaPurpose
import com.jiku.mfa.MfaStatus
import com.jiku.mfa.MfaSubject
import com.jiku.shared.AesGcmCipher
import com.jiku.shared.AttemptLimiter
import com.jiku.shared.CodedRefusal
import com.jiku.shared.JwtService
import com.jiku.shared.sha256Hex
import io.jsonwebtoken.JwtException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

@Service
class MfaService(
    private val enrollments: MfaEnrollmentRepository,
    private val backupCodes: MfaBackupCodeRepository,
    private val properties: MfaProperties,
    private val jwtService: JwtService,
    private val limiter: AttemptLimiter,
    transactionManager: PlatformTransactionManager,
) : MfaModuleApi {
    // Attempts are counted in their own transaction: a wrong code rolls the
    // caller's back, and the attempt must still count.
    private val ownTransaction =
        TransactionTemplate(transactionManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    private val cipher = AesGcmCipher(properties.encryptionKey, "auth.mfa.encryption-key")
    private val random = SecureRandom()

    override fun adminRequired(): Boolean = properties.adminRequired

    @Transactional(readOnly = true)
    override fun status(subject: MfaSubject): MfaStatus {
        val enrollment = confirmed(subject) ?: return MfaStatus(enabled = false, enabledAt = null, backupCodesLeft = 0)
        return MfaStatus(
            enabled = true,
            enabledAt = enrollment.confirmedAt,
            backupCodesLeft = backupCodes.countByEnrollmentIdAndUsedAtIsNull(requireNotNull(enrollment.id)),
        )
    }

    @Transactional
    override fun startEnrollment(
        subject: MfaSubject,
        accountLabel: String,
    ): MfaEnrollmentStart {
        val existing = find(subject)
        if (existing?.confirmedAt != null) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The second factor is already on")
        }
        val secret = Totp.newSecret()
        val enrollment = existing ?: MfaEnrollment(subject.kind, subject.id, cipher.encrypt(secret))
        enrollment.secret = cipher.encrypt(secret)
        enrollment.lastStep = null
        enrollments.save(enrollment)
        return MfaEnrollmentStart(secret = secret, otpauthUri = Totp.uri(properties.issuer, accountLabel, secret))
    }

    @Transactional
    override fun confirmEnrollment(
        subject: MfaSubject,
        code: String,
    ): List<String> {
        val enrollment = find(subject)?.takeIf { it.confirmedAt == null } ?: throw invalid()
        acceptAppCode(subject, enrollment, code)
        enrollment.confirmedAt = Instant.now()
        enrollments.save(enrollment)
        return replaceBackupCodes(enrollment)
    }

    @Transactional
    override fun verify(
        subject: MfaSubject,
        code: String?,
        backupCode: String?,
    ) {
        val enrollment = confirmed(subject) ?: throw invalid()
        when {
            !code.isNullOrBlank() -> acceptAppCode(subject, enrollment, code.trim())
            !backupCode.isNullOrBlank() -> acceptBackupCode(subject, enrollment, backupCode)
            else -> throw invalid()
        }
    }

    @Transactional
    override fun disable(
        subject: MfaSubject,
        code: String?,
        backupCode: String?,
    ) {
        verify(subject, code, backupCode)
        forget(subject)
    }

    @Transactional
    override fun renewBackupCodes(
        subject: MfaSubject,
        code: String,
    ): List<String> {
        val enrollment = confirmed(subject) ?: throw invalid()
        acceptAppCode(subject, enrollment, code.trim())
        return replaceBackupCodes(enrollment)
    }

    @Transactional
    override fun forget(subject: MfaSubject) {
        val enrollment = find(subject) ?: return
        backupCodes.deleteAllByEnrollmentId(requireNotNull(enrollment.id))
        enrollments.delete(enrollment)
    }

    override fun challenge(
        subject: MfaSubject,
        purpose: MfaPurpose,
    ): String =
        jwtService.sign(
            subject.id.toString(),
            mapOf(
                JwtService.CLAIM_TOKEN_TYPE to TOKEN_TYPE_CHALLENGE,
                CLAIM_KIND to subject.kind,
                CLAIM_PURPOSE to purpose.name,
            ),
            properties.challengeTtl,
        )

    override fun readChallenge(
        token: String,
        kind: String,
        purpose: MfaPurpose,
    ): UUID {
        val claims =
            try {
                jwtService.parse(token)
            } catch (ex: JwtException) {
                throw invalid()
            } catch (ex: IllegalArgumentException) {
                throw invalid()
            }
        if (claims[JwtService.CLAIM_TOKEN_TYPE] != TOKEN_TYPE_CHALLENGE ||
            claims[CLAIM_KIND] != kind ||
            claims[CLAIM_PURPOSE] != purpose.name
        ) {
            throw invalid()
        }
        return runCatching { UUID.fromString(claims.subject) }.getOrElse { throw invalid() }
    }

    override fun trustDevice(subject: MfaSubject): String =
        jwtService.sign(
            subject.id.toString(),
            mapOf(JwtService.CLAIM_TOKEN_TYPE to TOKEN_TYPE_TRUSTED_DEVICE, CLAIM_KIND to subject.kind),
            properties.trustedDeviceTtl,
        )

    @Transactional(readOnly = true)
    override fun isTrustedDevice(
        subject: MfaSubject,
        token: String?,
    ): Boolean {
        if (token.isNullOrBlank()) return false
        val enabledAt = confirmed(subject)?.confirmedAt ?: return false
        val claims = runCatching { jwtService.parse(token) }.getOrNull() ?: return false
        // The token's issue time is truncated to the second; one issued in the same
        // second the factor was turned on predates nothing that matters.
        return claims[JwtService.CLAIM_TOKEN_TYPE] == TOKEN_TYPE_TRUSTED_DEVICE &&
            claims[CLAIM_KIND] == subject.kind &&
            claims.subject == subject.id.toString() &&
            !claims.issuedAt.toInstant().isBefore(enabledAt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))
    }

    private fun acceptAppCode(
        subject: MfaSubject,
        enrollment: MfaEnrollment,
        code: String,
    ) {
        countAttempt(subject)
        val secret = cipher.decrypt(enrollment.secret)
        val step =
            Totp.matchingStep(secret, code, Instant.now().epochSecond, properties.clockSkewSteps)
                ?: throw invalid()
        val last = enrollment.lastStep
        if (last != null && step <= last) throw invalid()
        enrollment.lastStep = step
        enrollments.save(enrollment)
    }

    private fun acceptBackupCode(
        subject: MfaSubject,
        enrollment: MfaEnrollment,
        raw: String,
    ) {
        countAttempt(subject)
        val code =
            backupCodes.findByEnrollmentIdAndCodeHashAndUsedAtIsNull(requireNotNull(enrollment.id), hash(normalize(raw)))
                ?: throw invalid()
        code.usedAt = Instant.now()
        backupCodes.save(code)
    }

    private fun replaceBackupCodes(enrollment: MfaEnrollment): List<String> {
        val enrollmentId = requireNotNull(enrollment.id)
        backupCodes.deleteAllByEnrollmentId(enrollmentId)
        val codes = List(properties.backupCodes) { newBackupCode() }
        backupCodes.saveAll(codes.map { MfaBackupCode(enrollmentId, hash(normalize(it))) })
        return codes
    }

    /** Ten characters in two groups, from an alphabet without look-alikes (0/O, 1/I/L). */
    private fun newBackupCode(): String {
        val chars = List(BACKUP_CODE_LENGTH) { BACKUP_ALPHABET[random.nextInt(BACKUP_ALPHABET.length)] }.joinToString("")
        return chars.substring(0, BACKUP_CODE_LENGTH / 2) + "-" + chars.substring(BACKUP_CODE_LENGTH / 2)
    }

    private fun normalize(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    private fun countAttempt(subject: MfaSubject) {
        val retryAfter =
            ownTransaction.execute {
                limiter.tryAttempt("mfa:${subject.kind}:${subject.id}", properties.maxAttempts, properties.attemptWindow)
            }
        if (retryAfter != null) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many codes tried; please wait a moment")
        }
    }

    private fun find(subject: MfaSubject): MfaEnrollment? = enrollments.findBySubjectKindAndSubjectId(subject.kind, subject.id)

    private fun confirmed(subject: MfaSubject): MfaEnrollment? = find(subject)?.takeIf { it.confirmedAt != null }

    private fun invalid(): CodedRefusal =
        CodedRefusal(HttpStatus.UNAUTHORIZED, CodedRefusal.MFA_INVALID, "This code is not valid. Check it and try again.")

    private fun hash(value: String): String = sha256Hex(value)

    private companion object {
        const val TOKEN_TYPE_CHALLENGE = "mfa-challenge"
        const val TOKEN_TYPE_TRUSTED_DEVICE = "mfa-device"
        const val CLAIM_KIND = "kind"
        const val CLAIM_PURPOSE = "purpose"
        const val BACKUP_CODE_LENGTH = 10
        const val BACKUP_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    }
}
