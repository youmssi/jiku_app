package com.jiku.messaging

import com.jiku.TestcontainersConfiguration
import com.jiku.messaging.internal.NotificationLog
import com.jiku.messaging.internal.NotificationLogRepository
import com.jiku.messaging.internal.WhatsAppContentOverride
import com.jiku.messaging.internal.WhatsAppContentOverrideRepository
import com.jiku.messaging.internal.WhatsAppMessageCost
import com.jiku.messaging.internal.WhatsAppMessageCostRepository
import com.jiku.messaging.internal.WhatsAppPricing
import com.jiku.messaging.internal.WhatsAppProperties
import com.jiku.shared.GuestInvitedEvent
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JIKU-61: the WhatsApp conversation-window and content-category guardrails.
 * Both operate purely on recorded [WhatsAppMessageCost] rows and are exercised
 * here through the same synchronous event-listener path [NotificationOrchestrationTest]
 * uses, so no real Meta Cloud API call is ever made (the default `log` transport).
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class WhatsAppGuardrailsTest {
    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Autowired
    lateinit var logs: NotificationLogRepository

    @Autowired
    lateinit var costs: WhatsAppMessageCostRepository

    @Autowired
    lateinit var overrides: WhatsAppContentOverrideRepository

    @Autowired
    lateinit var notificationModuleApi: NotificationModuleApi

    @Autowired
    lateinit var properties: WhatsAppProperties

    @AfterEach
    fun cleanUp() {
        // Restore the seeded single row rather than deleting it — the repository
        // assumes exactly one exists (see WhatsAppContentOverride's KDoc).
        overrides.findFirstByOrderByUpdatedAtDesc()?.let {
            it.active = false
            overrides.save(it)
        }
        // The platform conversation-window count is global (native query, ignores
        // the tenant filter) — leftover rows from one test would otherwise push
        // the next test's sends over the safety threshold too.
        costs.deleteAll()
        TenantContext.clear()
    }

    private fun invite(
        invitationId: UUID,
        eventId: UUID,
        eventName: String = "Gala",
    ) {
        events.publishEvent(
            GuestInvitedEvent(
                invitationId = invitationId,
                tenantId = "tenant-whatsapp-guard",
                eventId = eventId,
                channel = GuestInvitedEvent.CHANNEL_WHATSAPP,
                recipient = "+224600000000",
                recipientName = "Ada Lovelace",
                eventName = eventName,
                eventWhen = null,
                eventLocation = null,
                organizerName = "Org",
                primaryColor = "#1E293B",
                logoUrl = null,
                invitationUrl = "http://localhost:3000/invitation/abc",
            ),
        )
    }

    @Test
    fun `send is queued not failed once the platform 24h conversation window is at the safety threshold`() {
        TenantContext.set("tenant-whatsapp-guard")
        val filler =
            (1..properties.conversationSafetyThreshold).map {
                WhatsAppMessageCost(
                    referenceId = null,
                    eventId = null,
                    pool = WhatsAppMessageCost.POOL_PLATFORM,
                    category = WhatsAppPricing.CATEGORY_UTILITY,
                    costUsdMinor = 4,
                    costGnfMinor = 4 * properties.usdToGnfRate,
                )
            }
        costs.saveAll(filler)

        val invitationId = UUID.randomUUID()
        invite(invitationId, UUID.randomUUID())

        val records = logs.findByReferenceId(invitationId)
        assertEquals(1, records.size)
        assertEquals(NotificationLog.STATUS_QUEUED, records.first().status)
    }

    @Test
    fun `a promotional body is blocked in the UTILITY category`() {
        TenantContext.set("tenant-whatsapp-guard")
        val invitationId = UUID.randomUUID()
        invite(invitationId, UUID.randomUUID(), eventName = "Soirée Promo Spéciale")

        val records = logs.findByReferenceId(invitationId)
        assertEquals(1, records.size)
        assertEquals(NotificationLog.STATUS_FAILED, records.first().status)
        assertTrue(
            records
                .first()
                .error
                .orEmpty()
                .contains("promotional", ignoreCase = true),
        )
    }

    @Test
    fun `an admin override lets promotional content send and records it at the MARKETING rate`() {
        TenantContext.set("tenant-whatsapp-guard")
        val override = overrides.findFirstByOrderByUpdatedAtDesc() ?: WhatsAppContentOverride()
        override.active = true
        override.reason = "test override"
        override.activatedBy = "admin-under-test"
        overrides.save(override)

        val invitationId = UUID.randomUUID()
        val eventId = UUID.randomUUID()
        invite(invitationId, eventId, eventName = "Soirée Promo Spéciale")

        val records = logs.findByReferenceId(invitationId)
        assertEquals(NotificationLog.STATUS_SENT, records.first().status)

        val cost = notificationModuleApi.eventWhatsAppCost(eventId)
        assertEquals(1, cost.messageCount)
        assertEquals(32, cost.costUsdMinor)
    }

    @Test
    fun `a successful send records cost retrievable aggregated by event`() {
        TenantContext.set("tenant-whatsapp-guard")
        val eventId = UUID.randomUUID()
        invite(UUID.randomUUID(), eventId)
        invite(UUID.randomUUID(), eventId)

        val cost = notificationModuleApi.eventWhatsAppCost(eventId)
        assertEquals(2, cost.messageCount)
        assertEquals(8, cost.costUsdMinor)
        assertEquals(8 * properties.usdToGnfRate, cost.costGnfMinor)
    }
}
