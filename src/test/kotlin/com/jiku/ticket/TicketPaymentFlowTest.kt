package com.jiku.ticket

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.invitation.internal.InvitationTokenService
import com.jiku.support.OrganizerApi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * JIKU-110: a ticket that costs something records what its holder owes the
 * organization. It lets no one in, and no service start, until an operator
 * confirms the payment; a service paid after it is rendered falls due when the
 * service ends.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class TicketPaymentFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var invitationTokens: InvitationTokenService

    private val api by lazy { OrganizerApi(mockMvc) }

    @Test
    fun `a sold event ticket opens the door only once the payment is confirmed`() {
        val token = api.register()
        api
            .put(token, "/api/v1/settings/payment-methods", """{"payeeName":"Gala Org","orangeMoneyNumber":"+224 620 00 00 00"}""")
            .andExpect(status().isOk())
        val eventId = api.createEvent(token)
        val typeId =
            JsonPath.read<String>(
                api
                    .post(token, "/api/v1/events/$eventId/ticket-types", """{"label":"Gala","priceMinor":150000}""")
                    .andReturn()
                    .response.contentAsString,
                "$.id",
            )
        api.publish(token, eventId)
        val guestId = api.importGuest(token, eventId, "Aminata")
        api
            .patch(token, "/api/v1/events/$eventId/guests/$guestId/ticket-type", """{"ticketTypeId":"$typeId"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rsvpStatus").value("PENDING"))
            .andExpect(jsonPath("$.ticketCode").doesNotExist())

        val rsvp = invitationTokens.issue(UUID.fromString(guestId), UUID.fromString(eventId), api.tenantId(token))
        val confirmed =
            mockMvc
                .perform(post("/api/v1/rsvp/$rsvp/confirm"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.status").value("DUE"))
                .andExpect(jsonPath("$.payment.amountMinor").value(150000))
                .andExpect(jsonPath("$.payment.currency").value("GNF"))
                .andExpect(jsonPath("$.payment.methods.orangeMoneyNumber").value("+224 620 00 00 00"))
                .andReturn()
                .response.contentAsString
        val code = JsonPath.read<String>(confirmed, "$.ticketCode")

        api
            .get(token, "/api/v1/events/$eventId/guests")
            .andExpect(jsonPath("$[0].rsvpStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$[0].ticketCode").value(code))
            .andExpect(jsonPath("$[0].paymentStatus").value("DUE"))
            .andExpect(jsonPath("$[0].amountDueMinor").value(150000))
            .andExpect(jsonPath("$[0].amountDueCurrency").value("GNF"))

        api
            .post(token, "/api/v1/events/$eventId/checkin/scan", """{"ticketCode":"$code"}""")
            .andExpect(jsonPath("$.outcome").value("PAYMENT_DUE"))
            .andExpect(jsonPath("$.amountDueMinor").value(150000))

        api
            .post(token, "/api/v1/events/$eventId/checkin/tickets/$code/paid", """{"method":"MOBILE_MONEY"}""")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.paymentStatus").value("PAID"))
        api
            .post(token, "/api/v1/events/$eventId/checkin/tickets/$code/paid", """{"method":"CASH"}""")
            .andExpect(status().isConflict())
        api
            .get(token, "/api/v1/events/$eventId/guests")
            .andExpect(jsonPath("$[0].paymentStatus").value("PAID"))

        api
            .post(token, "/api/v1/events/$eventId/checkin/scan", """{"ticketCode":"$code"}""")
            .andExpect(jsonPath("$.outcome").value("CHECKED_IN"))
        api
            .get(token, "/api/v1/events/$eventId/guests")
            .andExpect(jsonPath("$[0].checkedInAt").exists())
    }

    @Test
    fun `a free event ticket owes nothing and cannot be marked paid`() {
        val token = api.register()
        val eventId = api.createEvent(token)
        api.publish(token, eventId)
        val guestId = api.importGuest(token, eventId, "Moussa")
        val rsvp = invitationTokens.issue(UUID.fromString(guestId), UUID.fromString(eventId), api.tenantId(token))
        val confirmed =
            mockMvc
                .perform(post("/api/v1/rsvp/$rsvp/confirm"))
                .andExpect(jsonPath("$.payment").doesNotExist())
                .andReturn()
                .response.contentAsString
        val code = JsonPath.read<String>(confirmed, "$.ticketCode")

        api
            .get(token, "/api/v1/events/$eventId/guests")
            .andExpect(jsonPath("$[0].rsvpStatus").value("CONFIRMED"))
            .andExpect(jsonPath("$[0].paymentStatus").value("NOT_REQUIRED"))
        api
            .post(token, "/api/v1/events/$eventId/checkin/tickets/$code/paid", """{"method":"CASH"}""")
            .andExpect(status().isConflict())
    }

    @Test
    fun `a service paid before cannot start until paid`() {
        val token = api.register()
        val serviceId = createService(token, """"paymentRule":"BEFORE","priceMinor":100000""")
        val code = walkIn(token, serviceId)

        api.post(token, "/api/v1/services/$serviceId/day-line/tickets/$code/call", "{}").andExpect(status().isOk())
        api
            .post(token, "/api/v1/services/$serviceId/day-line/tickets/$code/present", "{}")
            .andExpect(status().isPaymentRequired())

        api
            .post(token, "/api/v1/services/$serviceId/day-line/tickets/$code/paid", """{"method":"CASH"}""")
            .andExpect(jsonPath("$.paymentStatus").value("PAID"))
        api.post(token, "/api/v1/services/$serviceId/day-line/tickets/$code/present", "{}").andExpect(status().isOk())
    }

    @Test
    fun `a service paid after falls due when it ends`() {
        val token = api.register()
        val serviceId = createService(token, """"paymentRule":"AFTER_SERVICE","priceMinor":20000""")
        val code = walkIn(token, serviceId)

        api.post(token, "/api/v1/services/$serviceId/day-line/tickets/$code/call", "{}").andExpect(status().isOk())
        api
            .post(token, "/api/v1/services/$serviceId/day-line/tickets/$code/present", "{}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticket.paymentStatus").value("DUE_AFTER_SERVICE"))
        api
            .post(token, "/api/v1/services/$serviceId/day-line/tickets/$code/finish", "{}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.ticket.paymentStatus").value("DUE"))
            .andExpect(jsonPath("$.ticket.amountDueMinor").value(20000))

        api
            .post(token, "/api/v1/services/$serviceId/day-line/tickets/$code/paid", """{"method":"MOBILE_MONEY"}""")
            .andExpect(jsonPath("$.paymentStatus").value("PAID"))
    }

    private fun createService(
        token: String,
        pricing: String,
    ): String =
        JsonPath.read(
            api
                .post(token, "/api/v1/services", """{"name":"Consultation","timezone":"Africa/Conakry",$pricing}""")
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.id",
        )

    private fun walkIn(
        token: String,
        serviceId: String,
    ): String =
        JsonPath.read(
            api
                .post(token, "/api/v1/services/$serviceId/day-line/walk-in", """{"clientName":"Awa","clientPhone":"+224620000001"}""")
                .andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString,
            "$.entries[0].ticketCode",
        )
}
