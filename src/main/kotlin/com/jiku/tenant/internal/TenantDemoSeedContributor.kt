package com.jiku.tenant.internal

import com.jiku.shared.DemoSeedContext
import com.jiku.shared.DemoSeedContributor
import com.jiku.shared.DemoSeedPlan
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * Resolves (creating on first run) the demo tenant and its organizer. The tenant
 * is kept across re-runs — only its business data is reset by the other modules —
 * so the login credentials stay stable. Runs first (`prepare`) so the resolved
 * tenant id is bound before any tenant-scoped seeding.
 */
@Component
class TenantDemoSeedContributor(
    private val authService: AuthService,
    private val users: OrganizerUserRepository,
    @param:Value("\${jiku.demo-seed.password:demo-password}") private val demoPassword: String,
) : DemoSeedContributor {
    override val order = 0

    override fun prepare(context: DemoSeedContext) {
        val existing = users.findByEmail(DemoSeedPlan.ORGANIZER_EMAIL)
        if (existing != null) {
            context.tenantId = existing.tenantId
            return
        }
        authService.register(
            RegisterRequest(DemoSeedPlan.TENANT_NAME, DemoSeedPlan.ORGANIZER_EMAIL, demoPassword),
        )
        context.tenantId = requireNotNull(users.findByEmail(DemoSeedPlan.ORGANIZER_EMAIL)).tenantId
    }
}
