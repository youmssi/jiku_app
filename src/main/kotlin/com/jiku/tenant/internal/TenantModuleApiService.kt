package com.jiku.tenant.internal

import com.jiku.tenant.TenantDirectoryEntry
import com.jiku.tenant.TenantDirectoryPage
import com.jiku.tenant.TenantInfo
import com.jiku.tenant.TenantModuleApi
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class TenantModuleApiService(
    private val tenants: TenantRepository,
    private val memberships: OrganizerMembershipRepository,
    private val users: OrganizerUserRepository,
    private val accountTokens: AccountTokenService,
    private val accessGate: TenantAccessGateAdapter,
    private val markets: MarketProperties,
) : TenantModuleApi {
    @Transactional(readOnly = true)
    override fun findTenant(tenantId: UUID): TenantInfo? = tenants.findById(tenantId).map { it.toTenantInfo() }.orElse(null)

    @Transactional(readOnly = true)
    override fun searchTenants(
        query: String?,
        page: Int,
        size: Int,
    ): TenantDirectoryPage {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE), Sort.by(Sort.Direction.DESC, "createdAt"))
        val result =
            if (query.isNullOrBlank()) {
                tenants.findAll(pageable)
            } else {
                tenants.search(query.trim(), pageable)
            }
        return TenantDirectoryPage(
            entries =
                result.content.map { tenant ->
                    val id = requireNotNull(tenant.id)
                    TenantDirectoryEntry(
                        id = id,
                        name = tenant.name,
                        contactEmail = tenant.contactEmail,
                        status = tenant.status.name,
                        createdAt = tenant.createdAt,
                        organizerCount = memberships.countByTenantId(id.toString()),
                    )
                },
            total = result.totalElements,
            page = result.number,
            size = result.size,
        )
    }

    @Transactional
    override fun setTenantSuspended(
        tenantId: UUID,
        suspended: Boolean,
    ): TenantInfo? {
        val tenant = tenants.findById(tenantId).orElse(null) ?: return null
        tenant.status = if (suspended) TenantStatus.SUSPENDED else TenantStatus.ACTIVE
        val saved = tenants.save(tenant)
        // The gate caches status lookups; a status change must be visible on the
        // very next request, not after the cache entry ages out.
        accessGate.invalidate(tenantId.toString())
        return saved.toTenantInfo()
    }

    @Transactional
    override fun provisionTenant(
        organizationName: String,
        ownerEmail: String,
        ownerFullName: String?,
    ): UUID {
        val user =
            users.findByEmail(ownerEmail)
                ?: users.saveAndFlush(
                    OrganizerUser(email = ownerEmail, passwordHash = null, fullName = ownerFullName).also {
                        it.emailVerified = true
                    },
                )
        // A booking customer never names a country: the organization opens in the default market.
        val market = requireNotNull(markets.marketOf(null))
        val tenant =
            tenants.saveAndFlush(
                Tenant(name = organizationName, contactEmail = ownerEmail, country = market.country, currency = market.currency),
            )
        memberships.saveAndFlush(
            OrganizerMembership(
                userId = requireNotNull(user.id),
                tenantId = requireNotNull(tenant.id).toString(),
                role = OrganizerRole.OWNER,
            ),
        )
        accountTokens.requestPasswordReset(ownerEmail)
        return requireNotNull(tenant.id)
    }

    @Transactional(readOnly = true)
    override fun findByUsername(username: String): TenantInfo? = tenants.findByUsernameIgnoreCase(username.trim())?.toTenantInfo()

    @Transactional
    override fun updateUsername(
        tenantId: UUID,
        username: String?,
    ): TenantInfo {
        val normalized = username?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        if (normalized != null) {
            val existing = tenants.findByUsernameIgnoreCase(normalized)
            if (existing != null && existing.id != tenantId) {
                throw org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.CONFLICT,
                    "This username is already taken",
                )
            }
        }
        val tenant =
            tenants.findById(tenantId).orElseThrow {
                org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND,
                    "Organization not found",
                )
            }
        tenant.username = normalized
        return tenants.save(tenant).toTenantInfo()
    }

    private companion object {
        const val MAX_PAGE_SIZE = 100
    }
}
