package com.jiku.tenant.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class AccountDeletionStatus {
    PENDING,
    CANCELLED,
    DONE,
}

/**
 * A request to delete an organizer account (JIKU-316). Not tenant-scoped: an
 * account spans organizations. The organizations it owned alone and chose to
 * delete are listed in [tenantIds].
 */
@Entity
@Table(name = "account_deletion")
class AccountDeletion(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,
    @Column(name = "email")
    var email: String?,
    @Column(name = "language", nullable = false, length = 8)
    val language: String,
    @Column(name = "tenant_ids", nullable = false)
    val tenantIds: String,
    @Column(name = "scheduled_for", nullable = false)
    val scheduledFor: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: AccountDeletionStatus = AccountDeletionStatus.PENDING

    @Column(name = "requested_at", nullable = false, updatable = false)
    val requestedAt: Instant = Instant.now()

    @Column(name = "finished_at")
    var finishedAt: Instant? = null

    fun tenants(): List<String> = tenantIds.split(',').filter { it.isNotBlank() }
}

interface AccountDeletionRepository : JpaRepository<AccountDeletion, UUID> {
    fun findFirstByUserIdAndStatus(
        userId: UUID,
        status: AccountDeletionStatus,
    ): AccountDeletion?

    fun findTop50ByStatusAndScheduledForBeforeOrderByScheduledForAsc(
        status: AccountDeletionStatus,
        before: Instant,
    ): List<AccountDeletion>
}

/** Account deletion settings (JIKU-316). */
@ConfigurationProperties(prefix = "auth.account-deletion")
data class AccountDeletionProperties(
    /** How long the holder has to change their mind by signing in again. */
    val grace: Duration = Duration.ofDays(30),
    /** How often due deletions are carried out. */
    val check: Duration = Duration.ofHours(1),
)
