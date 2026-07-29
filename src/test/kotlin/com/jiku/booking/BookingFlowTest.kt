package com.jiku.booking

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.admin.internal.PlatformAdmin
import com.jiku.admin.internal.PlatformAdminRepository
import com.jiku.billing.internal.UsageRecordRepository
import com.jiku.event.internal.EventRepository
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
 * JIKU-55 end to end: quoting and creating a reservation, declaring a Mobile
 * Money deposit, an admin verifying it — which must provision a tenant, an
 * owner account, and a pre-filled draft event with the reserved tier already
 * unlocked — a duplicate transaction reference being flagged rather than
 * silently accepted, a zero-deposit FREE-tier booking provisioning
 * immediately with no payment step, and cancellation computing the correct
 * refund.
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

    @Test
    fun `a verified deposit provisions a tenant, an account and a pre-filled event with the tier unlocked`() {
        val email = "bride-${UUID.randomUUID()}@test.example"
        val creation = createBooking(email = email, guestCount = 150)
        val bookingId = JsonPath.read<String>(creation, "$.id")
        val accessToken = JsonPath.read<String>(creation, "$.accessToken")
        assertEquals("BRONZE", JsonPath.read<String>(creation, "$.tier"))
        assertEquals(150_000, JsonPath.read<Int>(creation, "$.totalAmountMinor"))
        assertEquals(45_000, JsonPath.read<Int>(creation, "$.depositAmountMinor"))
        assertEquals("AWAITING_DEPOSIT", JsonPath.read<String>(creation, "$.status"))

        // The status page resolves by token, and rejects a wrong one.
        mockMvc
            .perform(get("/api/v1/bookings/$bookingId").param("token", accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tier").value("BRONZE"))
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
        val first = createBooking(email = "first-${UUID.randomUUID()}@test.example", guestCount = 150)
        val firstId = JsonPath.read<String>(first, "$.id")
        val firstToken = JsonPath.read<String>(first, "$.accessToken")
        mockMvc
            .perform(
                post("/api/v1/bookings/$firstId/payment-declarations")
                    .param("token", firstToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":45000,"kind":"DEPOSIT","operator":"ORANGE_MONEY","transactionReference":"$reference"}"""),
            ).andExpect(status().isCreated())
            .andExpect(jsonPath("$.verificationStatus").value("PENDING"))

        val second = createBooking(email = "second-${UUID.randomUUID()}@test.example", guestCount = 150)
        val secondId = JsonPath.read<String>(second, "$.id")
        val secondToken = JsonPath.read<String>(second, "$.accessToken")
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
    fun `a FREE-tier booking has no deposit and provisions immediately`() {
        val email = "free-${UUID.randomUUID()}@test.example"
        val creation = createBooking(email = email, guestCount = 40)
        assertEquals("FREE", JsonPath.read<String>(creation, "$.tier"))
        assertEquals(0, JsonPath.read<Int>(creation, "$.totalAmountMinor"))
        assertEquals(0, JsonPath.read<Int>(creation, "$.depositAmountMinor"))
        assertEquals("DEPOSIT_PAID", JsonPath.read<String>(creation, "$.status"))
        val user = users.findByEmail(email)
        assertNotNull(user)
    }

    @Test
    fun `cancelling a booking computes the refund due under the sliding scale`() {
        val creation =
            createBooking(email = "cancel-${UUID.randomUUID()}@test.example", guestCount = 150, eventDate = LocalDate.now().plusDays(90))
        val bookingId = JsonPath.read<String>(creation, "$.id")
        val adminToken = adminLogin()
        mockMvc
            .perform(post("/api/v1/admin/bookings/$bookingId/cancel").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andExpect(jsonPath("$.refundAmountMinor").value(45_000))
    }

    private fun createBooking(
        email: String,
        guestCount: Int,
        eventDate: LocalDate = LocalDate.now().plusYears(1),
    ): String =
        mockMvc
            .perform(
                post("/api/v1/bookings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"customerName":"Test Customer","customerPhone":"+224600000000","customerEmail":"$email",
                         "eventType":"MARIAGE","eventDate":"$eventDate","guestCountEstimate":$guestCount}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isCreated())
            .andReturn()
            .response.contentAsString

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
