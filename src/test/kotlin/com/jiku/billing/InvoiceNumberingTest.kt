package com.jiku.billing

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.billing.internal.DocumentType
import com.jiku.billing.internal.InvoiceRepository
import com.jiku.billing.internal.InvoiceService
import com.jiku.billing.internal.Payment
import com.jiku.billing.internal.PaymentRepository
import com.jiku.billing.internal.PaymentStatus
import com.jiku.shared.TenantContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.Executors

/**
 * JIKU-69: the two properties that make an invoice worth anything to an auditor —
 * numbers are **gapless** within a tenant's fiscal year, and one tenant can never
 * see or disturb another's sequence.
 *
 * A hole in the numbering is precisely what a tax audit asks about, so these are
 * not incidental assertions; they are the story.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class InvoiceNumberingTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var service: InvoiceService

    @Autowired
    lateinit var invoices: InvoiceRepository

    @Autowired
    lateinit var payments: PaymentRepository

    @Autowired
    lateinit var transactions: TransactionTemplate

    @AfterEach
    fun clearContext() = TenantContext.clear()

    @Test
    fun `numbers are gapless and sequential within a fiscal year`() {
        val tenantId = organizationReadyToInvoice("gapless")

        val issued = withTenant(tenantId) { (1..5).map { service.issueForPayment(settledPayment()) } }

        assertThat(issued.map { it.sequenceNumber }).containsExactly(1L, 2L, 3L, 4L, 5L)
        assertThat(issued.map { it.invoiceNumber }.toSet()).hasSize(5)
    }

    @Test
    fun `a rolled back issuance does not consume a number`() {
        val tenantId = organizationReadyToInvoice("rollback")

        val first = withTenant(tenantId) { service.issueForPayment(settledPayment()) }
        assertThat(first.sequenceNumber).isEqualTo(1L)

        val doomed = withTenant(tenantId) { settledPayment() }
        // The tenant has to be bound *before* the transaction starts: Hibernate
        // resolves the tenant identifier when the session opens, so setting it
        // inside would leave the reads scoped to nothing.
        assertThatThrownBy {
            withTenant(tenantId) {
                transactions.execute {
                    service.issueForPayment(doomed)
                    throw IllegalStateException("deliberate rollback")
                }
            }
        }.hasMessageContaining("deliberate rollback")

        // The number the rolled-back attempt took has to come back, or the
        // sequence has a hole in it.
        val next = withTenant(tenantId) { service.issueForPayment(settledPayment()) }
        assertThat(next.sequenceNumber).isEqualTo(2L)
    }

    @Test
    fun `concurrent issuance never repeats or skips a number`() {
        val tenantId = organizationReadyToInvoice("concurrent")
        val paymentIds = withTenant(tenantId) { (1..6).map { settledPayment() } }

        val executor = Executors.newFixedThreadPool(6)
        try {
            val sequences =
                paymentIds
                    .map { paymentId ->
                        executor.submit<Long> {
                            TenantContext.set(tenantId)
                            try {
                                service.issueForPayment(paymentId).sequenceNumber
                            } finally {
                                TenantContext.clear()
                            }
                        }
                    }.map { it.get() }

            assertThat(sequences.sorted()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L)
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `each tenant has its own sequence and cannot read another's invoices`() {
        val alphaTenant = organizationReadyToInvoice("alpha")
        val alpha = withTenant(alphaTenant) { service.issueForPayment(settledPayment()) }

        val betaTenant = organizationReadyToInvoice("beta")
        val beta = withTenant(betaTenant) { service.issueForPayment(settledPayment()) }

        // Both start at one: the sequence is per tenant, not platform-wide.
        assertThat(alpha.sequenceNumber).isEqualTo(1L)
        assertThat(beta.sequenceNumber).isEqualTo(1L)

        assertThat(withTenant(betaTenant) { invoices.findAll().map { it.id } }).containsExactly(beta.id)
        assertThat(withTenant(alphaTenant) { invoices.findAll().map { it.id } }).containsExactly(alpha.id)
    }

    @Test
    fun `an invoice is refused until the organization's legal details are complete`() {
        val token = register("incomplete")
        val tenantId = tenantId(token)

        assertThatThrownBy { withTenant(tenantId) { service.issueForPayment(settledPayment()) } }
            .hasMessageContaining("legal details")
    }

    @Test
    fun `issuing twice for one payment returns the same invoice rather than burning a number`() {
        val tenantId = organizationReadyToInvoice("idempotent")

        val paymentId = withTenant(tenantId) { settledPayment() }
        val first = withTenant(tenantId) { service.issueForPayment(paymentId) }
        val second = withTenant(tenantId) { service.issueForPayment(paymentId) }

        assertThat(second.id).isEqualTo(first.id)
        assertThat(withTenant(tenantId) { service.issueForPayment(settledPayment()).sequenceNumber }).isEqualTo(2L)
    }

    @Test
    fun `a credit note reverses the invoice it corrects and can only be issued once`() {
        val tenantId = organizationReadyToInvoice("credit")

        val invoice = withTenant(tenantId) { service.issueForPayment(settledPayment()) }
        val credit = withTenant(tenantId) { service.issueCreditNote(requireNotNull(invoice.id)) }

        assertThat(credit.documentType).isEqualTo(DocumentType.CREDIT_NOTE)
        assertThat(credit.correctedInvoiceId).isEqualTo(invoice.id)
        assertThat(credit.totalMinor).isEqualTo(-invoice.totalMinor)
        // The original is untouched — that is the whole point of a credit note.
        assertThat(withTenant(tenantId) { service.find(requireNotNull(invoice.id)).totalMinor })
            .isEqualTo(invoice.totalMinor)

        assertThatThrownBy { withTenant(tenantId) { service.issueCreditNote(requireNotNull(invoice.id)) } }
            .hasMessageContaining("already been credited")
    }

    private fun settledPayment(): UUID {
        val payment =
            Payment(
                eventId = UUID.randomUUID(),
                tier = "ARGENT",
                amountMinor = 300_000,
                currency = "GNF",
                provider = "SANDBOX",
            ).apply { status = PaymentStatus.SUCCEEDED }
        return requireNotNull(payments.saveAndFlush(payment).id)
    }

    /** A registered organization whose legal details are filled in over HTTP. */
    private fun organizationReadyToInvoice(label: String): String {
        val token = register(label)
        mockMvc
            .perform(
                put("/api/v1/legal-identity")
                    .header("Authorization", "Bearer $token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"legalName":"Societe $label SARL","registrationNumber":"RCCM-123",
                         "taxIdentifier":"NIF-456","addressLine":"12 rue du Commerce",
                         "city":"Conakry","country":"GN"}
                        """.trimIndent(),
                    ),
            ).andExpect(status().isOk())
        return tenantId(token)
    }

    private fun register(label: String): String {
        val body =
            mockMvc
                .perform(
                    post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"name":"Invoice $label Org","email":"inv-$label-${UUID.randomUUID()}@test.example","password":"supersecret"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.accessToken")
    }

    private fun tenantId(token: String): String {
        val body =
            mockMvc
                .perform(get("/api/v1/auth/me").header("Authorization", "Bearer $token"))
                .andReturn()
                .response.contentAsString
        return JsonPath.read(body, "$.tenantId")
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
}
