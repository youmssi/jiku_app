package com.jiku.tenant.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A single-use, time-boxed token mailed to an account owner (JIKU-49): password
 * reset or email verification. Only the SHA-256 hash is stored — the raw token
 * exists solely in the emailed link, so a database leak exposes nothing usable.
 */
@Entity
@Table(name = "account_token")
class AccountToken(
    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, updatable = false)
    val purpose: AccountTokenPurpose,
    @Column(name = "token_hash", nullable = false, updatable = false, unique = true)
    val tokenHash: String,
    @Column(name = "expires_at", nullable = false, updatable = false)
    val expiresAt: Instant,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null

    @Column(name = "used_at")
    var usedAt: Instant? = null

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
}

enum class AccountTokenPurpose {
    PASSWORD_RESET,
    EMAIL_VERIFICATION,
}
