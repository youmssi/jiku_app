package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.money.internal.SubscriptionExpiryJob
import com.jiku.money.internal.SubscriptionRepository
import com.jiku.money.internal.SubscriptionStatus
import com.jiku.shared.TenantContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Abonnement prépayé par ressource active (JIKU-90). Couvre le DoD : le cycle
 * complet expiration → grâce → suspension → réactivation, le signalement de
 * dépassement sans blocage immédiat, la matérialisation à la première ressource
 * active et la demande de prépaiement idempotente sur le circuit manuel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class SubscriptionFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var subscriptions: SubscriptionRepository

    @Autowired
    lateinit var expiryJob: SubscriptionExpiryJob

    @Autowired
    lateinit var billingModuleApi: BillingModuleApi

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `first active resource opens a subscription that signals over-limit without blocking`() {
        val token = register("Solo Org", uniqueEmail())
        val tenantId = currentTenantId(token)

        // La première ressource active matérialise un abonnement Solo (1 ressource).
        createResource(token)
        val first = subscriptionJson(token)
        assertEquals("Solo", JsonPath.read<String>(first, "$.plan"))
        assertEquals(1, JsonPath.read<Int>(first, "$.resourcesIncluded"))
        assertEquals(1, JsonPath.read<Int>(first, "$.resourcesActive"))
        assertEquals(false, JsonPath.read<Boolean>(first, "$.overLimit"))

        // Racheter Équipe (jusqu'à 5 ressources) puis dépasser : signalé, jamais bloqué.
        requestAndConfirm(token, plan = "Équipe", months = 1)
        repeat(5) { createResource(token) }
        val after = subscriptionJson(token)
        assertEquals(6, JsonPath.read<Int>(after, "$.resourcesActive"))
        assertEquals(5, JsonPath.read<Int>(after, "$.resourcesIncluded"))
        assertEquals(true, JsonPath.read<Boolean>(after, "$.overLimit"))
        assertEquals("ACTIVE", JsonPath.read<String>(after, "$.status"))

        TenantContext.set(tenantId)
        val row = subscriptions.findCurrent().single()
        assertTrue(row.resourcesActive == 6L && row.resourceLimit == 5L)
        TenantContext.clear()
    }

    @Test
    fun `expiry leads to grace then suspension then reactivation on payment`() {
        val token = register("Cycle Org", uniqueEmail())
        val tenantId = currentTenantId(token)
        createResource(token)

        // J-7 : l'échéance approche, le préavis part une seule fois.
        forceExpiry(tenantId, Instant.now().plusSeconds(86400))
        expiryJob.sweep()
        TenantContext.set(tenantId)
        val noticed = subscriptions.findCurrent().single()
        assertTrue(noticed.expiryNoticeSent)
        TenantContext.clear()

        // Demande de prépaiement Équipe 3 mois (ouverte pendant la période active).
        val requested = requestSubscription(token, plan = "Équipe", months = 3)
        val paymentId = JsonPath.read<String>(requested, "$.paymentId")
        assertEquals(712_500, JsonPath.read<Int>(requested, "$.amountMinor"))
        val again = requestSubscription(token, plan = "Équipe", months = 3)
        assertEquals(paymentId, JsonPath.read<String>(again, "$.paymentId"))

        // Échéance atteinte → grâce : l'organisateur reste joignable, non suspendu.
        forceExpiry(tenantId, Instant.now().minusSeconds(60))
        expiryJob.sweep()
        val inGrace = subscriptionJson(token)
        assertEquals("GRACE", JsonPath.read<String>(inGrace, "$.status"))
        assertTrue(JsonPath.read<String>(inGrace, "$.suspensionAt") != null)

        // Fin de grâce → expiration et suspension par le kill-switch.
        forceExpiry(tenantId, Instant.now().minusSeconds(3L * 86400))
        expiryJob.sweep()
        TenantContext.set(tenantId)
        assertEquals(SubscriptionStatus.EXPIRED, subscriptions.findCurrent().single().status)
        TenantContext.clear()
        mockMvc
            .perform(get("/api/v1/billing/subscription").header("Authorization", "Bearer $token"))
            .andExpect(status().is4xxClientError())

        // La confirmation du paiement réactive immédiatement (et lève la suspension).
        billingModuleApi.adminConfirmManualPayment(UUID.fromString(paymentId))
        val reactivated = subscriptionJson(token)
        assertEquals("ACTIVE", JsonPath.read<String>(reactivated, "$.status"))
        assertEquals("Équipe", JsonPath.read<String>(reactivated, "$.plan"))
        assertEquals(5, JsonPath.read<Int>(reactivated, "$.resourcesIncluded"))
        assertTrue(JsonPath.read<String>(reactivated, "$.expiresAt") > JsonPath.read<String>(reactivated, "$.startedAt"))
    }

    private fun forceExpiry(
        tenantId: String,
        expiresAt: Instant,
    ) {
        TenantContext.set(tenantId)
        subscriptions.save(
            subscriptions.findCurrent().single().apply {
                this.expiresAt = expiresAt
                updatedAt = Instant.now()
            },
        )
        TenantContext.clear()
    }

    private fun subscriptionJson(token: String): String =
        mockMvc
            .perform(get("/api/v1/billing/subscription").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk())
            .andReturn()
            .response
            .contentAsString

    private fun requestSubscription(
        token: String,
        plan: String,
        months: Int,
    ): String =
        mockMvc
            .perform(
                post("/api/v1/billing/subscription/request")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"plan":"$plan","months":$months}"""),
            ).andExpect(status().isOk())
            .andReturn()
            .response
            .contentAsString

    private fun requestAndConfirm(
        token: String,
        plan: String,
        months: Int,
    ) {
        val body = requestSubscription(token, plan, months)
        val paymentId = JsonPath.read<String>(body, "$.paymentId")
        billingModuleApi.adminConfirmManualPayment(UUID.fromString(paymentId))
    }

    private fun createResource(token: String) {
        mockMvc
            .perform(
                post("/api/v1/resources")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Poste ${UUID.randomUUID()}","type":"PERSON","timezone":"Africa/Conakry"}"""),
            ).andExpect(status().is2xxSuccessful())
    }

    private fun register(
        name: String,
        email: String,
    ): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"name":"$name","email":"$email","password":"supersecret"}"""),
                ).andExpect(status().isCreated())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun currentTenantId(accessToken: String): String {
        val body =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $accessToken"))
                .andExpect(status().isOk())
                .andReturn()
                .response
                .contentAsString
        return JsonPath.read(body, "$.tenantId")
    }

    private fun uniqueEmail(): String =
        "sub-${
            UUID.randomUUID().toString().take(8)
        }@test.example"
}
