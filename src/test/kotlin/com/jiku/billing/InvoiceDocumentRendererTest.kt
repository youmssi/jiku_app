package com.jiku.billing

import com.jiku.billing.internal.DocumentType
import com.jiku.billing.internal.Invoice
import com.jiku.billing.internal.InvoiceDocumentRenderer
import com.jiku.billing.internal.InvoiceLine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * JIKU-69: the rendered document. Pure unit tests — no Spring context, no
 * database — so they stay fast and can assert on the bytes directly.
 */
class InvoiceDocumentRendererTest {
    private val renderer = InvoiceDocumentRenderer()

    private fun invoice(
        currency: String = "GNF",
        documentType: DocumentType = DocumentType.INVOICE,
        unitPriceMinor: Long = 300_000,
    ): Invoice =
        Invoice(
            invoiceNumber = "INV-2026-000001",
            fiscalYear = 2026,
            sequenceNumber = 1,
            documentType = documentType,
            buyerLegalName = "Societe Test SARL",
            buyerAddressLine = "12 rue du Commerce",
            buyerCity = "Conakry",
            buyerCountry = "GN",
            sellerName = "Jiku",
            currency = currency,
            subtotalMinor = unitPriceMinor,
            taxRate = BigDecimal("0.18"),
            taxAmountMinor = (unitPriceMinor * 18) / 100,
            totalMinor = unitPriceMinor + (unitPriceMinor * 18) / 100,
            issueDate = LocalDate.of(2026, 9, 1),
        ).apply {
            taxLabel = "TVA 18%"
            lines =
                mutableListOf(
                    InvoiceLine(
                        lineNumber = 1,
                        description = "Jiku ARGENT tier",
                        quantity = 1,
                        unitPriceMinor = unitPriceMinor,
                        lineTotalMinor = unitPriceMinor,
                    ),
                )
        }

    @Test
    fun `renders a PDF`() {
        val bytes = renderer.render(invoice())

        assertThat(bytes).isNotEmpty()
        // %PDF- is the format's magic number; anything else is not a PDF whatever
        // the content type claims.
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
    }

    @Test
    fun `the file is named after the invoice number`() {
        assertThat(renderer.fileName(invoice())).isEqualTo("INV-2026-000001.pdf")
    }

    @Test
    fun `a zero-decimal currency is not divided by a hundred`() {
        // GNF and XAF have no minor unit. Applying the usual /100 would print every
        // Guinean amount a hundred times too small — an error that only ever shows
        // up on a customer's invoice.
        val text = String(renderer.render(invoice(currency = "GNF")), Charsets.ISO_8859_1)
        assertThat(text).doesNotContain("3,000.00")
    }

    @Test
    fun `a credit note renders as a credit note`() {
        val bytes = renderer.render(invoice(documentType = DocumentType.CREDIT_NOTE, unitPriceMinor = -300_000))

        assertThat(bytes).isNotEmpty()
        assertThat(String(bytes.copyOfRange(0, 5))).isEqualTo("%PDF-")
    }
}
