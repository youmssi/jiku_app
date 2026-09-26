package com.jiku.support

import com.jiku.shared.TenantContext
import com.jiku.tenant.internal.OrganizationVerification
import com.jiku.tenant.internal.OrganizationVerificationRepository
import com.jiku.tenant.internal.VerificationKind
import com.jiku.tenant.internal.VerificationStatus
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Marks an organization verified, as the Jikū team would after reviewing its
 * documents (JIKU-175): tests of steps that make clients pay start from here.
 */
@Component
class TestVerifications(
    private val verifications: OrganizationVerificationRepository,
) {
    fun approve(tenantId: String) {
        TenantContext.withTenant(tenantId) {
            verifications.save(
                OrganizationVerification(
                    kind = VerificationKind.COMPANY,
                    legalName = "Test Org SARL",
                    documentType = "RCCM",
                    registrationNumber = "GN-TEST",
                ).apply {
                    status = VerificationStatus.APPROVED
                    decidedAt = Instant.now()
                },
            )
        }
    }
}
