package com.jiku.invitation

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.TicketOrderService
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * JIKU-177, plan de production 6.1: a client orders tickets, the places are
 * held while it pays the organization, and the organization confirms or
 * refuses. Places are taken all at once and given back on expiry or refusal;
 * two buyers racing for the last place never both get it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TicketOrderFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var verifications: TestVerifications

    @Autowired
    lateinit var orders: TicketOrderService

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a client orders tickets, declares its payment, and gets its tickets once the organization confirms`() {
        val sale = openSale(capacity = 3)
        publicSale(sale)
            .andExpect(jsonPath("$.onSale").value(true))
            .andExpect(jsonPath("$.categories[0].available").value(3))
            .andExpect(jsonPath("$.categories[0].priceMinor").value(50000))
            .andExpect(jsonPath("$.holdMinutes").value(30))

        val placed =
            place(sale, quantity = 2)
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val orderToken = JsonPath.read<String>(placed, "$.token")
        assertEquals("AWAITING_PAYMENT", JsonPath.read(placed, "$.order.status"))
        assertEquals(100000, JsonPath.read<Int>(placed, "$.order.totalMinor"))
        assertEquals("+224 620 00 00 00", JsonPath.read(placed, "$.order.paymentMethods.orangeMoneyNumber"))
        publicSale(sale).andExpect(jsonPath("$.categories[0].available").value(1))

        order(orderToken, "/declare", """{"paymentReference":"OM-4471"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DECLARED"))
            .andExpect(jsonPath("$.paymentReference").value("OM-4471"))
        order(orderToken, "/declare", """{"paymentReference":"OM-0000"}""").andExpect(status().isConflict())

        val listed =
            api
                .get(sale.token, "/api/v1/events/${sale.eventId}/orders?status=DECLARED")
                .andReturn()
                .response.contentAsString
        val orderId = JsonPath.read<String>(listed, "$[0].id")
        assertEquals(2, JsonPath.read<Int>(listed, "$[0].ticketCount"))

        api
            .post(sale.token, "/api/v1/events/${sale.eventId}/orders/$orderId/confirm", "{}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("PAID"))
        api
            .post(sale.token, "/api/v1/events/${sale.eventId}/orders/$orderId/confirm", "{}")
            .andExpect(status().isConflict())

        val paid =
            order(orderToken)
                .andExpect(jsonPath("$.status").value("PAID"))
                .andReturn()
                .response.contentAsString
        val tickets = JsonPath.read<List<String>>(paid, "$.ticketTokens")
        assertEquals(2, tickets.size)
        mockMvc
            .perform(get("/api/v1/rsvp/${tickets[0]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.guestName").value("Aminata Camara"))
            .andExpect(jsonPath("$.payment.status").value("PAID"))
        publicSale(sale).andExpect(jsonPath("$.categories[0].available").value(1))
    }

    @Test
    fun `an order larger than the places left takes nothing`() {
        val sale = openSale(capacity = 2)
        place(sale, quantity = 3).andExpect(status().isConflict())
        publicSale(sale).andExpect(jsonPath("$.categories[0].available").value(2))
    }

    @Test
    fun `an unverified organization cannot sell`() {
        val sale = openSale(capacity = 5, verified = false)
        publicSale(sale)
            .andExpect(jsonPath("$.onSale").value(false))
            .andExpect(jsonPath("$.closedReason").value("ORGANIZER_NOT_VERIFIED"))
        place(sale, quantity = 1).andExpect(status().isConflict())
    }

    @Test
    fun `an unpaid order gives its places back once its hold passes, a declared one does not`() {
        val sale = openSale(capacity = 4)
        api.put(sale.token, "/api/v1/settings/sales", """{"orderHoldMinutes":5}""").andExpect(status().isBadRequest())
        api
            .put(sale.token, "/api/v1/settings/sales", """{"orderHoldMinutes":15}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.effectiveOrderHoldMinutes").value(15))
        publicSale(sale).andExpect(jsonPath("$.holdMinutes").value(15))

        val unpaid = JsonPath.read<String>(place(sale, quantity = 2).andReturn().response.contentAsString, "$.token")
        val declared = JsonPath.read<String>(place(sale, quantity = 1).andReturn().response.contentAsString, "$.token")
        order(declared, "/declare", """{"paymentReference":"MTN-99"}""").andExpect(status().isOk())
        publicSale(sale).andExpect(jsonPath("$.categories[0].available").value(1))

        val expired = TenantContext.withTenant(sale.tenantId) { orders.expireOverdue(Instant.now().plus(Duration.ofMinutes(16))) }
        assertEquals(1, expired)
        order(unpaid).andExpect(jsonPath("$.status").value("EXPIRED")).andExpect(jsonPath("$.paymentMethods").doesNotExist())
        order(unpaid, "/declare", """{"paymentReference":"late"}""").andExpect(status().isConflict())
        order(declared).andExpect(jsonPath("$.status").value("DECLARED"))
        publicSale(sale).andExpect(jsonPath("$.categories[0].available").value(3))
    }

    @Test
    fun `a refused order gives its places back and tells the client why`() {
        val sale = openSale(capacity = 2)
        val orderToken = JsonPath.read<String>(place(sale, quantity = 2).andReturn().response.contentAsString, "$.token")
        val orderId =
            JsonPath.read<String>(
                api
                    .get(sale.token, "/api/v1/events/${sale.eventId}/orders")
                    .andReturn()
                    .response.contentAsString,
                "$[0].id",
            )
        api
            .post(sale.token, "/api/v1/events/${sale.eventId}/orders/$orderId/reject", """{"reason":"No payment received"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("REJECTED"))
        order(orderToken)
            .andExpect(jsonPath("$.status").value("REJECTED"))
            .andExpect(jsonPath("$.rejectionReason").value("No payment received"))
        publicSale(sale).andExpect(jsonPath("$.categories[0].available").value(2))
        api
            .post(sale.token, "/api/v1/events/${sale.eventId}/orders/$orderId/confirm", "{}")
            .andExpect(status().isConflict())
    }

    @Test
    fun `another organization cannot see or decide an order`() {
        val sale = openSale(capacity = 2)
        place(sale, quantity = 1).andExpect(status().isCreated())
        val orderId =
            JsonPath.read<String>(
                api
                    .get(sale.token, "/api/v1/events/${sale.eventId}/orders")
                    .andReturn()
                    .response.contentAsString,
                "$[0].id",
            )
        val other = api.register()
        api
            .get(other, "/api/v1/events/${sale.eventId}/orders")
            .andExpect(jsonPath("$.length()").value(0))
        api
            .post(other, "/api/v1/events/${sale.eventId}/orders/$orderId/confirm", "{}")
            .andExpect(status().isNotFound())
    }

    @Test
    fun `buyers racing for the last place never both get it`() {
        val sale = openSale(capacity = 1)
        val buyers = 8
        val ready = CountDownLatch(buyers)
        val go = CountDownLatch(1)
        val created = AtomicInteger()
        val refused = AtomicInteger()
        val pool = Executors.newFixedThreadPool(buyers)
        repeat(buyers) {
            pool.submit {
                ready.countDown()
                go.await()
                when (place(sale, quantity = 1).andReturn().response.status) {
                    201 -> created.incrementAndGet()
                    409 -> refused.incrementAndGet()
                }
            }
        }
        ready.await()
        go.countDown()
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.SECONDS)

        assertEquals(1, created.get())
        assertEquals(buyers - 1, refused.get())
        publicSale(sale).andExpect(jsonPath("$.categories[0].available").value(0))
    }

    private data class Sale(
        val token: String,
        val tenantId: String,
        val username: String,
        val eventId: String,
        val typeId: String,
    )

    private fun openSale(
        capacity: Int,
        verified: Boolean = true,
    ): Sale {
        val token = api.register()
        val tenantId = api.tenantId(token)
        verifications.approve(tenantId)
        api
            .put(token, "/api/v1/settings/payment-methods", """{"payeeName":"Gala Org","orangeMoneyNumber":"+224 620 00 00 00"}""")
            .andExpect(status().isOk())
        val username = "sale-${UUID.randomUUID().toString().take(8)}"
        api.put(token, "/api/v1/orgs/username", """{"username":"$username"}""").andExpect(status().isOk())
        val eventId = api.createEvent(token)
        val typeId =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"Gala","maxCapacity":$capacity,"priceMinor":50000}""")
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        if (!verified) verifications.revoke(tenantId)
        return Sale(token, tenantId, username, eventId, typeId)
    }

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

    private fun order(
        token: String,
        action: String = "",
        body: String? = null,
    ): ResultActions {
        val request =
            if (body == null) {
                get("/api/v1/orders/$token$action")
            } else {
                post("/api/v1/orders/$token$action").contentType(MediaType.APPLICATION_JSON).content(body)
            }
        return mockMvc.perform(request)
    }

    private fun randomIp(): String = "10.${(0..255).random()}.${(0..255).random()}.${(1..254).random()}"
}
