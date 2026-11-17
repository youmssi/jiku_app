package com.jiku.mfa.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * One account's authentication-app secret (JIKU-317), encrypted at rest. It is
 * enforced only once [confirmedAt] is set by a first valid code. [lastStep] is
 * the newest 30-second step a code was accepted for, so a code cannot be used
 * twice.
 */
@Entity
@Table(name = "mfa_enrollment")
class MfaEnrollment(
    @Column(name = "subject_kind", nullable = false, updatable = false, length = 16)
    val subjectKind: String,
    @Column(name = "subject_id", nullable = false, updatable = false)
    val subjectId: UUID,
    @Column(name = "secret", nullable = false)
    var secret: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null

    @Column(name = "last_step")
    var lastStep: Long? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

/** A single-use backup code, stored as its SHA-256 hash. */
@Entity
@Table(name = "mfa_backup_code")
class MfaBackupCode(
    @Column(name = "enrollment_id", nullable = false, updatable = false)
    val enrollmentId: UUID,
    @Column(name = "code_hash", nullable = false, updatable = false, length = 64)
    val codeHash: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "used_at")
    var usedAt: Instant? = null
}

interface MfaEnrollmentRepository : JpaRepository<MfaEnrollment, UUID> {
    fun findBySubjectKindAndSubjectId(
        subjectKind: String,
        subjectId: UUID,
    ): MfaEnrollment?
}

interface MfaBackupCodeRepository : JpaRepository<MfaBackupCode, UUID> {
    fun findByEnrollmentIdAndCodeHashAndUsedAtIsNull(
        enrollmentId: UUID,
        codeHash: String,
    ): MfaBackupCode?

    fun countByEnrollmentIdAndUsedAtIsNull(enrollmentId: UUID): Int

    @Modifying
    @Query("delete from MfaBackupCode c where c.enrollmentId = :enrollmentId")
    fun deleteAllByEnrollmentId(enrollmentId: UUID)
}

/**
 * Second-factor settings (JIKU-317), all overridable by environment variable:
 * how long the code step stays open, how long a trusted device skips it, how
 * many codes may be tried per window, and the key encrypting the secrets.
 */
@ConfigurationProperties(prefix = "auth.mfa")
data class MfaProperties(
    val encryptionKey: String,
    val issuer: String = "Jikū",
    val challengeTtl: Duration = Duration.ofMinutes(5),
    val trustedDeviceTtl: Duration = Duration.ofDays(30),
    val maxAttempts: Int = 5,
    val attemptWindow: Duration = Duration.ofMinutes(5),
    val clockSkewSteps: Int = 1,
    val backupCodes: Int = 10,
    /** Whether desk administrators must have the factor before they may sign in. */
    val adminRequired: Boolean = true,
)
