package com.jiku

import com.jiku.shared.DemoSeedContext
import com.jiku.shared.DemoSeedContributor
import com.jiku.shared.DemoSeedPlan
import com.jiku.shared.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Orchestrates demo-tenant seeding (JIKU-9D). It coordinates only: each module
 * contributes its own slice through the exposed [DemoSeedContributor] interface,
 * so no code outside a module ever touches its internals. The tenant is resolved
 * first (via `prepare`), then bound so tenant-filtered writes land in the demo
 * tenant; wipe runs highest-order-first (dependents before dependencies) and seed
 * runs lowest-first, both making re-runs safe without any manual cleanup.
 *
 * Run it with one command against whatever database the POSTGRES_* variables point
 * at: `./gradlew seedDemoData`.
 */
@Component
class DemoDataSeeder(
    contributors: List<DemoSeedContributor>,
    private val context: ConfigurableApplicationContext,
    @param:Value("\${jiku.demo-seed.password:demo-password}") private val demoPassword: String,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(DemoDataSeeder::class.java)

    // Fixed order regardless of bean discovery order.
    private val contributors = contributors.sortedBy { it.order }

    override fun run(args: ApplicationArguments) {
        if (!args.containsOption("seed-demo")) {
            return
        }
        val summary = seed()
        log.info(
            "Demo tenant ready: log in as {} / {} (tenant {}) — {} events, {} guests",
            DemoSeedPlan.ORGANIZER_EMAIL,
            demoPassword,
            summary.tenantId,
            summary.eventCount,
            summary.guestCount,
        )
        // Started only to seed (see the seedDemoData Gradle task): shut down once done.
        context.close()
    }

    /** Resets and recreates the demo tenant's data. Returns what was seeded. */
    fun seed(): SeedSummary {
        val seedContext = DemoSeedContext()
        contributors.forEach { it.prepare(seedContext) }
        TenantContext.set(seedContext.tenantId)
        try {
            contributors.asReversed().forEach { it.wipe() }
            contributors.forEach { it.seed(seedContext) }
        } finally {
            TenantContext.clear()
        }
        return SeedSummary(
            tenantId = seedContext.tenantId,
            eventCount = seedContext.eventIds.size,
            guestCount = seedContext.guestIds.size,
            draftEventId = seedContext.eventIds.getValue(DemoSeedPlan.EVENT_LAUNCH),
            upcomingEventId = seedContext.eventIds.getValue(DemoSeedPlan.EVENT_GALA),
            pastEventId = seedContext.eventIds.getValue(DemoSeedPlan.EVENT_CONFERENCE),
        )
    }

    data class SeedSummary(
        val tenantId: String,
        val eventCount: Int,
        val guestCount: Int,
        val draftEventId: UUID,
        val upcomingEventId: UUID,
        val pastEventId: UUID,
    )
}
