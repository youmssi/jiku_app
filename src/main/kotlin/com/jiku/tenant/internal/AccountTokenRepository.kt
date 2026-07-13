package com.jiku.tenant.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AccountTokenRepository : JpaRepository<AccountToken, UUID> {
    fun findByTokenHash(tokenHash: String): AccountToken?

    fun deleteByUserIdAndPurposeAndUsedAtIsNull(
        userId: UUID,
        purpose: AccountTokenPurpose,
    )
}
