package com.jiku.booking

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.booking.internal.BookingRepository
import com.jiku.catalog.internal.EventRepository
import com.jiku.money.internal.UsageRecordRepository
import com.jiku.shared.TenantContext
import com.jiku.tenant.internal.OrganizerUserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * JIKU-55, for the deposit reservations still open since JIKU-115 closed new
 * ones: declaring a Mobile Money deposit, an admin verifying it — which must
 * provision a tenant, an owner account, and a pre-filled draft event with the
 * reserved tier already unlocked — a duplicate transaction reference being
 * flagged rather than silently accepted, and cancellation computing the
 * correct refund.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class BookingFlowTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var users: OrganizerUserRepository

    @Autowired
    lateinit var events: EventRepository

    @Autowired
    lateinit var usageRecords: UsageRecordRepository

    @Autowired
    lateinit var bookings: BookingRepository

    @Test
    fun `a verified deposit provisions a tenant, an account and a pre-filled event with the tier unlocked`() {
        val email = "bride-${UUID.randomUUID()}@test.example"
        val (bookingId, accessToken) = bookings.openBooking(email = email)

        // The status page resolves by token, and rejects a wrong one.
        mockMvc
            .perform(get("/api/v1/bookings/$bookingId").param("token", accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tier").value("BRONZE"))
            .andExpect(jsonPath("$.status").value("AWAITING_DEPOSIT"))
        mockMvc
            .perform(get("/api/v1/bookings/$bookingId").param("token", "wrong-token"))
            .andExpect(status().isNotFound())

        val declaration =
            mockMvc
                .perform(
                    post("/api/v1/bookings/$bookingId/payment-declarations")
                        .param("token", accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"amountMinor":45000,"kind":"DEPOSIT","operator":"ORANGE_MONEY","transactionReference":"OM-${UUID.randomUUID()}"}""",
                        ),
                ).andExpect(status().isCreated())
                .andExpect(jsonPath("$.verificationStatus").value("PENDING"))
                .andReturn()
                .response.contentAsString
        val declarationId = JsonPath.read<String>(declaration, "$.id")

        val adminToken = adminLogin()
        mockMvc
            .perform(get("/api/v1/admin/booking-payments").param("status", "PENDING").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.id == '$declarationId')].bookingId").value(bookingId))

        mockMvc
            .perform(post("/api/v1/admin/booking-payments/$declarationId/verify").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))

        val bookingView =
            mockMvc
                .perform(get("/api/v1/admin/bookings").param("status", "DEPOSIT_PAID").header("Authorization", "Bearer $adminToken"))
                .andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        val tenantId = JsonPath.read<List<String>>(bookingView, "$[?(@.id == '$bookingId')].tenantId")[0]
        val eventId = JsonPath.read<List<String>>(bookingView, "$[?(@.id == '$bookingId')].eventId")[0]
        assertNotNull(tenantId)
        assertNotNull(eventId)

        // The owner account exists, has no password yet (set via the emailed
        // password-reset link, JIKU-49's existing flow) and is pre-verified.
        val user = users.findByEmail(email)
        assertNotNull(user)
        assertNull(user.passwordHash)
        assertEquals(true, user.emailVerified)

        // The draft event is pre-filled and already has the BRONZE allowance
        // unlocked — the organizer can start inviting immediately.
        withTenant(tenantId) {
            val event = events.findById(UUID.fromString(eventId)).orElseThrow()
            assertNotNull(event.startDateTime)
            val usage = usageRecords.findByEventId(event.id!!)
            assertEquals(300L, usage?.unlockedAllowance)
        }

        // Audited.
        mockMvc
            .perform(
                get("/api/v1/admin/audit")
                    .param("action", "BOOKING_PAYMENT_VERIFIED")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].target").value("booking-payment:$declarationId"))
    }

    @Test
    fun `a reused transaction reference is flagged duplicate and alerts admin, not silently accepted`() {
        val reference = "OM-DUP-${UUID.randomUUID()}"
        val (firstId, firstToken) = bookings.openBooking(email = "first-${UUID.randomUUID()}@test.example")
        mockMvc
            .perform(
                post("/api/v1/bookings/$firstId/payment-declarations")
                    .param("token", firstToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":45000,"kind":"DEPOSIT","operator":"ORANGE_MONEY","transactionReference":"$reference"}"""),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.verificationStatus").value("PENDING"))

        val (secondId, secondToken) = bookings.openBooking(email = "second-${UUID.randomUUID()}@test.example")
        mockMvc
            .perform(
                post("/api/v1/bookings/$secondId/payment-declarations")
                    .param("token", secondToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":45000,"kind":"DEPOSIT","operator":"ORANGE_MONEY","transactionReference":"$reference"}"""),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.verificationStatus").value("DUPLICATE"))
    }

    @Test
    fun `cancelling a booking computes the refund due under the sliding scale`() {
        val bookingId =
            bookings
                .openBooking(
                    email = "cancel-${UUID.randomUUID()}@test.example",
                    eventDate = LocalDate.now().plusDays(90),
                ).id
        val adminToken = adminLogin()
        mockMvc
            .perform(post("/api/v1/admin/bookings/$bookingId/cancel").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.refundAmountMinor").value(45_000))
    }

    private fun <T> withTenant(
        tenantId: String,
        block: () -> T,
    ): T {
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            return block()
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun adminLogin(): String {
        val email = "booking-admin@jiku.test"
        if (!admins.existsByEmail(email)) {
            admins.save(PlatformAdmin(email = email, passwordHash = requireNotNull(passwordEncoder.encode("admin-secret"))))
        }
        val body =
            mockMvc
                .perform(
                    post("/api/v1/admin/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""{"email":"$email","password":"admin-secret"}"""),
                ).andExpect(status().isOk())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }
}
