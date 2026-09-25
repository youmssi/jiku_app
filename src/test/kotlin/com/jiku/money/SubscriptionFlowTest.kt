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
 * Services subscription priced per team (JIKU-90, ADR 105): the first person
 * opens a free Solo plan that never expires, places and equipment do not count,
 * outgrowing Solo opens a window to choose a paid plan, a team plan is priced
 * for its size, and the expiry → grace → suspension → reactivation cycle holds.
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
    fun `one person is free for good, a second opens the window to choose a team plan`() {
        val token = register("Solo Org", uniqueEmail())

        createResource(token)
        createResource(token, type = "LOCATION")
        val solo = subscriptionJson(token)
        assertEquals("Solo", JsonPath.read<String>(solo, "$.plan"))
        assertEquals("GNF", JsonPath.read<String>(solo, "$.currency"))
        assertEquals(1, JsonPath.read<Int>(solo, "$.resourcesActive"), "a place is not a person who serves")
        assertEquals(0, JsonPath.read<Int>(solo, "$.monthlyMinor"))
        assertEquals(null, JsonPath.read<String?>(solo, "$.expiresAt"), "a free plan never ends")
        assertEquals(false, JsonPath.read<Boolean>(solo, "$.overLimit"))

        createResource(token)
        val outgrown = subscriptionJson(token)
        assertEquals(true, JsonPath.read<Boolean>(outgrown, "$.overLimit"))
        assertTrue(JsonPath.read<String?>(outgrown, "$.expiresAt") != null, "outgrowing Solo starts the window to choose")
        assertEquals(150_000, JsonPath.read<List<Int>>(outgrown, "$.plans[?(@.name == 'Teams')].teamMonthlyMinor").single())

        requestAndConfirm(token, plan = "Teams", months = 1)
        createResource(token)
        val teams = subscriptionJson(token)
        assertEquals("Teams", JsonPath.read<String>(teams, "$.plan"))
        assertEquals(false, JsonPath.read<Boolean>(teams, "$.overLimit"))
        assertEquals(200_000, JsonPath.read<Int>(teams, "$.monthlyMinor"), "150 000 for two people, 50 000 for the third")
    }

    @Test
    fun `a free plan cannot be bought and Solo Plus cannot hold a team`() {
        val token = register("Free Org", uniqueEmail())
        createResource(token)
        createResource(token)

        requestSubscription(token, plan = "Solo", months = 1, expected = 400)
        requestSubscription(token, plan = "Solo Plus", months = 1, expected = 409)
        requestSubscription(token, plan = "Teams", months = 3, expected = 400)
    }

    @Test
    fun `expiry leads to grace then suspension then reactivation on payment`() {
        val token = register("Cycle Org", uniqueEmail())
        val tenantId = currentTenantId(token)
        repeat(2) { createResource(token) }

        forceExpiry(tenantId, Instant.now().plusSeconds(86400))
        expiryJob.sweep()
        TenantContext.set(tenantId)
        val noticed = subscriptions.findCurrent().single()
        assertTrue(noticed.expiryNoticeSent)
        TenantContext.clear()

        val requested = requestSubscription(token, plan = "Teams", months = 12)
        val paymentId = JsonPath.read<String>(requested, "$.paymentId")
        assertEquals(1_500_000, JsonPath.read<Int>(requested, "$.amountMinor"), "a year of Teams charges ten months")
        val again = requestSubscription(token, plan = "Teams", months = 12)
        assertEquals(paymentId, JsonPath.read<String>(again, "$.paymentId"))

        forceExpiry(tenantId, Instant.now().minusSeconds(60))
        expiryJob.sweep()
        val inGrace = subscriptionJson(token)
        assertEquals("GRACE", JsonPath.read<String>(inGrace, "$.status"))
        assertTrue(JsonPath.read<String>(inGrace, "$.suspensionAt") != null)

        forceExpiry(tenantId, Instant.now().minusSeconds(3L * 86400))
        expiryJob.sweep()
        TenantContext.set(tenantId)
        assertEquals(SubscriptionStatus.EXPIRED, subscriptions.findCurrent().single().status)
        TenantContext.clear()
        mockMvc
            .perform(get("/api/v1/billing/subscription").header("Authorization", "Bearer $token"))
            .andExpect(status().is4xxClientError())

        billingModuleApi.adminConfirmManualPayment(UUID.fromString(paymentId))
        val reactivated = subscriptionJson(token)
        assertEquals("ACTIVE", JsonPath.read<String>(reactivated, "$.status"))
        assertEquals("Teams", JsonPath.read<String>(reactivated, "$.plan"))
        assertEquals(2, JsonPath.read<Int>(reactivated, "$.resourcesIncluded"))
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
        expected: Int = 200,
    ): String =
        mockMvc
            .perform(
                post("/api/v1/billing/subscription/request")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"plan":"$plan","months":$months}"""),
            ).andExpect(status().`is`(expected))
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

    private fun createResource(
        token: String,
        type: String = "PERSON",
    ) {
        mockMvc
            .perform(
                post("/api/v1/resources")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"name":"Poste ${UUID.randomUUID()}","type":"$type","timezone":"Africa/Conakry"}"""),
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
