package com.jiku.booking

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.backoffice.internal.PlatformAdmin
import com.jiku.backoffice.internal.PlatformAdminRepository
import com.jiku.booking.internal.BookingRefundRepository
import com.jiku.money.internal.DocumentType
import com.jiku.money.internal.InvoiceRepository
import com.jiku.shared.TenantContext
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
import kotlin.test.assertTrue

/**
 * Remboursements opérationnels (JIKU-75) — DoD : remboursement total ou partiel
 * enregistré contre l'acompte d'origine, avoir CREDIT_NOTE produit, motif
 * obligatoire, refus du sur-remboursement, journalisé.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class BookingRefundTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var admins: PlatformAdminRepository

    @Autowired
    lateinit var passwordEncoder: PasswordEncoder

    @Autowired
    lateinit var refunds: BookingRefundRepository

    @Autowired
    lateinit var invoices: InvoiceRepository

    @Test
    fun `a partial refund is recorded against the deposit, produces a credit note and is audited`() {
        val email = "refund-${UUID.randomUUID()}@test.example"
        val creation = createBooking(email = email)
        val bookingId = JsonPath.read<String>(creation, "$.id")
        val accessToken = JsonPath.read<String>(creation, "$.accessToken")

        // Acompte déclaré puis vérifié → tenant + événement provisionnés.
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
                .andReturn()
                .response.contentAsString
        val declarationId = JsonPath.read<String>(declaration, "$.id")
        val adminToken = adminLogin()
        mockMvc
            .perform(post("/api/v1/admin/booking-payments/$declarationId/verify").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))

        // Annulation (JIKU-55) puis remboursement partiel exécuté.
        mockMvc
            .perform(post("/api/v1/admin/bookings/$bookingId/cancel").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANCELLED"))

        val refunded =
            mockMvc
                .perform(
                    post("/api/v1/admin/bookings/$bookingId/refund")
                        .header("Authorization", "Bearer $adminToken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"amountMinor":20000,"reason":"Annulation à 90 jours — remboursement partiel à la demande du client"}""",
                        ),
                ).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.amountMinor").value(20000))
                .andExpect(jsonPath("$.creditNoteNumber").isNotEmpty)
                .andReturn()
                .response.contentAsString
        val creditNote = JsonPath.read<String>(refunded, "$.creditNoteNumber")
        assertTrue(creditNote.startsWith("CN-"))

        // Enregistré contre la déclaration d'acompte, motif conservé.
        val rows = refunds.findByBookingIdOrderByExecutedAtDesc(UUID.fromString(bookingId))
        assertEquals(1, rows.size)
        assertEquals(20000, rows.single().amountMinor)
        assertEquals(declarationId, rows.single().declarationId.toString())
        assertEquals(creditNote, rows.single().creditNoteNumber)

        // L'avoir existe dans le tenant de l'organisateur.
        val bookingView =
            mockMvc
                .perform(get("/api/v1/admin/bookings").param("status", "REFUNDED").header("Authorization", "Bearer $adminToken"))
                .andReturn()
                .response.contentAsString
        val tenantId = JsonPath.read<List<String>>(bookingView, "$[?(@.id == '$bookingId')].tenantId")[0]
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            val notes = invoices.findAll().filter { it.documentType == DocumentType.CREDIT_NOTE && it.invoiceNumber == creditNote }
            assertEquals(1, notes.size)
            assertEquals(-20000, notes.single().totalMinor)
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }

        // Journalisé.
        mockMvc
            .perform(
                get("/api/v1/admin/audit")
                    .param("action", "BOOKING_REFUNDED")
                    .header("Authorization", "Bearer $adminToken"),
            ).andExpect(status().isOk())
            .andExpect(jsonPath("$.entries[0].target").value("booking:$bookingId"))

        // Sur-remboursement refusé.
        mockMvc
            .perform(
                post("/api/v1/admin/bookings/$bookingId/refund")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":40000,"reason":"Test sur-remboursement"}"""),
            ).andExpect(status().isConflict())
    }

    @Test
    fun `a refund requires a reason`() {
        val creation = createBooking(email = "reason-${UUID.randomUUID()}@test.example")
        val bookingId = JsonPath.read<String>(creation, "$.id")
        val adminToken = adminLogin()
        mockMvc
            .perform(post("/api/v1/admin/bookings/$bookingId/cancel").header("Authorization", "Bearer $adminToken"))
            .andExpect(status().isOk())
        mockMvc
            .perform(
                post("/api/v1/admin/bookings/$bookingId/refund")
                    .header("Authorization", "Bearer $adminToken")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"amountMinor":1000,"reason":"   "}"""),
            ).andExpect(status().isBadRequest())
    }

    private fun createBooking(email: String): String {
        val eventDate = LocalDate.now().plusDays(90)
        return mockMvc
            .perform(
                post("/api/v1/bookings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"customerName":"Test Customer","customerPhone":"+224600000000","customerEmail":"$email",
                         "eventType":"MARIAGE","eventDate":"$eventDate","guestCountEstimate":150}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isCreated())
            .andReturn()
            .response.contentAsString
    }

    private fun adminLogin(): String {
        val email = "refund-admin@jiku.test"
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
