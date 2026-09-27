package com.jiku.money

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.money.internal.CommissionService
import com.jiku.shared.TenantContext
import com.jiku.support.OrganizerApi
import com.jiku.support.TestVerifications
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * JIKU-178 (ADR 104 §8, ADR 105 decision 4): the 3 % commission on tickets
 * sold, paid by batch before selling. First batch free, one on credit, a sale
 * paused when its batches are used up except on the event's day, and the
 * unused part of a paid batch carried over as a credit.
 */
@SpringBootTest(
    properties = [
        "sales.commission.batch-size=2",
        "billing.payment.webhook-secret=test-payment-secret",
    ],
)
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class CommissionFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var verifications: TestVerifications

    @Autowired
    lateinit var commission: CommissionService

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `the first batch is free, the sale pauses when it is used up, and a paid batch reopens it`() {
        val sale = openSale()
        publicSale(sale)
            .andExpect(jsonPath("$.onSale").value(false))
            .andExpect(jsonPath("$.closedReason").value("PAUSED"))
            .andExpect(jsonPath("$.categories[0].paused").value(true))
        place(sale, 1).andExpect(status().isConflict())

        overview(sale).andExpect(jsonPath("$.freeBatchAvailable").value(true)).andExpect(jsonPath("$.ratePercent").value("3"))
        openBatch(sale, "FREE").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"))
        openBatch(sale, "FREE").andExpect(status().isConflict())
        publicSale(sale).andExpect(jsonPath("$.onSale").value(true)).andExpect(jsonPath("$.categories[0].available").value(2))

        val order =
            place(sale, 2)
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        publicSale(sale).andExpect(jsonPath("$.categories[0].paused").value(true))
        confirmFirstOrder(sale)
        overview(sale)
            .andExpect(jsonPath("$.categories[0].covered").value(0))
            .andExpect(jsonPath("$.categories[0].sold").value(2))
        assertEquals("AWAITING_PAYMENT", JsonPath.read(order, "$.order.status"))

        api
            .get(sale.token, "/api/v1/events/${sale.eventId}/commission/quote?ticketTypeId=${sale.typeId}")
            .andExpect(jsonPath("$.size").value(2))
            .andExpect(jsonPath("$.unitCommissionMinor").value(1500))
            .andExpect(jsonPath("$.totalMinor").value(3000))
        val opened =
            openBatch(sale, "PAY")
                .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                .andReturn()
                .response.contentAsString
        publicSale(sale).andExpect(jsonPath("$.categories[0].paused").value(true))

        pay(sale.tenantId, JsonPath.read(opened, "$.payment.paymentId"))
        publicSale(sale).andExpect(jsonPath("$.onSale").value(true)).andExpect(jsonPath("$.categories[0].available").value(2))
    }

    @Test
    fun `a batch on credit is settled with the next payment, and closing turns an unused paid batch into credit`() {
        val sale = openSale()
        openBatch(sale, "FREE").andExpect(status().isOk())
        overview(sale).andExpect(jsonPath("$.creditBatchAvailable").value(true))
        openBatch(sale, "CREDIT").andExpect(jsonPath("$.funding").value("CREDIT"))
        overview(sale)
            .andExpect(jsonPath("$.owedMinor").value(3000))
            .andExpect(jsonPath("$.creditBatchAvailable").value(false))
            .andExpect(jsonPath("$.categories[0].covered").value(4))
        openBatch(sale, "CREDIT").andExpect(status().isConflict())

        val opened =
            openBatch(sale, "PAY")
                .andExpect(jsonPath("$.totalMinor").value(6000))
                .andReturn()
                .response.contentAsString
        pay(sale.tenantId, JsonPath.read(opened, "$.payment.paymentId"))
        overview(sale).andExpect(jsonPath("$.owedMinor").value(0)).andExpect(jsonPath("$.categories[0].covered").value(6))

        place(sale, 1).andExpect(status().isCreated())
        confirmFirstOrder(sale)

        // The ticket sold used the free batch; the two paid ones (the credit batch, settled, and the last) are unused.
        TenantContext.withTenant(sale.tenantId) { commission.closeDue(Instant.now().plus(Duration.ofDays(800))) }
        overview(sale)
            .andExpect(jsonPath("$.creditMinor").value(6000))
            .andExpect(jsonPath("$.owedMinor").value(0))
    }

    @Test
    fun `on the event's day a sale never pauses, and what no batch covered is owed after`() {
        val sale = openSale(startsToday = true)
        overview(sale).andExpect(jsonPath("$.onEventDay").value(true))
        publicSale(sale).andExpect(jsonPath("$.onSale").value(true)).andExpect(jsonPath("$.categories[0].paused").value(false))

        place(sale, 3).andExpect(status().isCreated())
        confirmFirstOrder(sale)
        overview(sale).andExpect(jsonPath("$.owedMinor").value(0)).andExpect(jsonPath("$.categories[0].sold").value(3))

        TenantContext.withTenant(sale.tenantId) { commission.closeDue(Instant.now().plus(Duration.ofDays(3))) }
        overview(sale)
            .andExpect(jsonPath("$.owedMinor").value(4500))
            .andExpect(jsonPath("$.freeBatchAvailable").value(false))
            .andExpect(jsonPath("$.creditBatchAvailable").value(false))
    }

    @Test
    fun `two confirmations at once never consume the same place`() {
        val sale = openSale()
        openBatch(sale, "FREE").andExpect(status().isOk())
        place(sale, 1).andExpect(status().isCreated())
        place(sale, 1).andExpect(status().isCreated())
        val ids =
            JsonPath.read<List<String>>(
                api
                    .get(sale.token, "/api/v1/events/${sale.eventId}/orders")
                    .andReturn()
                    .response.contentAsString,
                "$[*].id",
            )
        val ready = CountDownLatch(ids.size)
        val go = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(ids.size)
        ids.forEach { orderId ->
            pool.submit {
                ready.countDown()
                go.await()
                api.post(sale.token, "/api/v1/events/${sale.eventId}/orders/$orderId/confirm", "{}")
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        overview(sale)
            .andExpect(jsonPath("$.categories[0].covered").value(0))
            .andExpect(jsonPath("$.categories[0].sold").value(2))
        TenantContext.withTenant(sale.tenantId) { commission.closeDue(Instant.now().plus(Duration.ofDays(800))) }
        overview(sale).andExpect(jsonPath("$.owedMinor").value(0))
    }

    private data class Sale(
        val token: String,
        val tenantId: String,
        val username: String,
        val eventId: String,
        val typeId: String,
    )

    private fun openSale(startsToday: Boolean = false): Sale {
        val token = api.register()
        val tenantId = api.tenantId(token)
        verifications.approve(tenantId)
        api
            .put(token, "/api/v1/settings/payment-methods", """{"payeeName":"Gala Org","orangeMoneyNumber":"+224 620 00 00 00"}""")
            .andExpect(status().isOk())
        val username = "fee-${UUID.randomUUID().toString().take(8)}"
        api.put(token, "/api/v1/orgs/username", """{"username":"$username"}""").andExpect(status().isOk())
        val eventId =
            if (startsToday) {
                val start = Instant.now().plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS)
                JsonPath.read(
                    api
                        .post(
                            token,
                            "/api/v1/events",
                            """{"name":"Tonight","timezone":"Africa/Conakry","startDateTime":"$start","invitationChannels":["EMAIL"]}""",
                        ).andExpect(status().isCreated())
                        .andReturn()
                        .response.contentAsString,
                    "$.id",
                )
            } else {
                api.createEvent(token)
            }
        val typeId =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"Gala","maxCapacity":10,"priceMinor":50000}""")
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        return Sale(token, tenantId, username, eventId, typeId)
    }

    private fun overview(sale: Sale): ResultActions =
        api.get(sale.token, "/api/v1/events/${sale.eventId}/commission").andExpect(status().isOk())

    private fun openBatch(
        sale: Sale,
        mode: String,
    ): ResultActions =
        api.post(sale.token, "/api/v1/events/${sale.eventId}/commission/batches", """{"ticketTypeId":"${sale.typeId}","mode":"$mode"}""")

    private fun publicSale(sale: Sale): ResultActions =
        mockMvc.perform(
            get("/api/v1/public/orgs/${sale.username}/events/${sale.eventId}").with {
                it.remoteAddr = randomIp()
                it
            },
        )

    private fun place(
        sale: Sale,
        quantity: Int,
    ): ResultActions =
        mockMvc.perform(
            post("/api/v1/public/orgs/${sale.username}/events/${sale.eventId}/orders")
                .with {
                    it.remoteAddr = randomIp()
                    it
                }.contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"lines":[{"ticketTypeId":"${sale.typeId}","quantity":$quantity}],""" +
                        """"buyerName":"Aminata Camara","buyerPhone":"+224 621 11 22 33"}""",
                ),
        )

    private fun confirmFirstOrder(sale: Sale) {
        val orderId =
            JsonPath.read<String>(
                api
                    .get(sale.token, "/api/v1/events/${sale.eventId}/orders?status=AWAITING_PAYMENT")
                    .andReturn()
                    .response.contentAsString,
                "$[0].id",
            )
        api.post(sale.token, "/api/v1/events/${sale.eventId}/orders/$orderId/confirm", "{}").andExpect(status().isOk())
    }

    private fun pay(
        tenantId: String,
        paymentId: String,
    ) {
        val body = """{"reference":"$tenantId:$paymentId","providerReference":"SANDBOX-x","status":"SUCCEEDED"}"""
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec("test-payment-secret".toByteArray(), "HmacSHA256")) }
        val signature = mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
        mockMvc
            .perform(
                post("/api/v1/billing/payments/callback")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Signature", signature)
                    .content(body),
            ).andExpect(status().isOk())
    }

    private fun randomIp(): String = "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
}
