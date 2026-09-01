package com.jiku.billing.internal

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.FontFactory
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

/**
 * Renders an [Invoice] as a PDF (JIKU-69).
 *
 * Everything printed comes from the invoice row itself — both parties, the tax
 * rate, every total. Nothing is looked up live, so re-downloading a document
 * years later reproduces exactly what the buyer received, which is the property
 * that makes an invoice worth anything in an audit.
 */
@Component
class InvoiceDocumentRenderer {
    fun render(invoice: Invoice): ByteArray {
        val output = ByteArrayOutputStream()
        val document = Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN)
        PdfWriter.getInstance(document, output)
        document.open()
        try {
            document.add(heading(invoice))
            document.add(parties(invoice))
            document.add(lines(invoice))
            document.add(totals(invoice))
            invoice.correctedInvoiceId?.let {
                document.add(
                    Paragraph("This credit note corrects invoice $it.", SMALL).apply {
                        spacingBefore = 16f
                    },
                )
            }
        } finally {
            document.close()
        }
        return output.toByteArray()
    }

    /** Filename a browser saves the download as; the number is the stable identifier. */
    fun fileName(invoice: Invoice): String = "${invoice.invoiceNumber}.pdf"

    private fun heading(invoice: Invoice): Paragraph {
        val title = if (invoice.documentType == DocumentType.CREDIT_NOTE) "Credit note" else "Invoice"
        return Paragraph().apply {
            add(Paragraph(title, TITLE))
            add(Paragraph(invoice.invoiceNumber, SUBTITLE))
            add(Paragraph("Issued ${invoice.issueDate.format(DATE)}", SMALL))
            spacingAfter = 18f
        }
    }

    private fun parties(invoice: Invoice): PdfPTable =
        PdfPTable(2).apply {
            widthPercentage = 100f
            setSpacingAfter(18f)
            addCell(
                partyCell(
                    "From",
                    listOfNotNull(invoice.sellerName, invoice.sellerAddressLine, taxLine(invoice.sellerTaxIdentifier)),
                ),
            )
            addCell(
                partyCell(
                    "Billed to",
                    listOfNotNull(
                        invoice.buyerLegalName,
                        invoice.buyerAddressLine,
                        "${invoice.buyerCity}, ${invoice.buyerCountry}",
                        invoice.buyerRegistrationNumber?.let { "Reg. $it" },
                        taxLine(invoice.buyerTaxIdentifier),
                    ),
                ),
            )
        }

    private fun taxLine(identifier: String?): String? = identifier?.let { "Tax ID $it" }

    private fun partyCell(
        label: String,
        rows: List<String>,
    ): PdfPCell =
        PdfPCell().apply {
            border = com.lowagie.text.Rectangle.NO_BORDER
            addElement(Paragraph(label, LABEL))
            rows.forEach { addElement(Paragraph(it, BODY)) }
        }

    private fun lines(invoice: Invoice): PdfPTable =
        PdfPTable(floatArrayOf(5f, 1f, 2f, 2f)).apply {
            widthPercentage = 100f
            setSpacingAfter(12f)
            listOf("Description", "Qty", "Unit price", "Amount").forEach { addCell(headerCell(it)) }
            invoice.lines.forEach { line ->
                addCell(bodyCell(line.description, Element.ALIGN_LEFT))
                addCell(bodyCell(line.quantity.toString(), Element.ALIGN_RIGHT))
                addCell(bodyCell(money(line.unitPriceMinor, invoice.currency), Element.ALIGN_RIGHT))
                addCell(bodyCell(money(line.lineTotalMinor, invoice.currency), Element.ALIGN_RIGHT))
            }
        }

    private fun totals(invoice: Invoice): PdfPTable =
        PdfPTable(floatArrayOf(6f, 3f)).apply {
            widthPercentage = 100f
            horizontalAlignment = Element.ALIGN_RIGHT
            addCell(bodyCell("Subtotal", Element.ALIGN_RIGHT))
            addCell(bodyCell(money(invoice.subtotalMinor, invoice.currency), Element.ALIGN_RIGHT))
            // The tax row is printed even at zero so the document never leaves a
            // reader guessing whether tax was considered.
            addCell(bodyCell(invoice.taxLabel ?: "Tax (${percent(invoice.taxRate)})", Element.ALIGN_RIGHT))
            addCell(bodyCell(money(invoice.taxAmountMinor, invoice.currency), Element.ALIGN_RIGHT))
            addCell(totalCell("Total"))
            addCell(totalCell(money(invoice.totalMinor, invoice.currency)))
        }

    private fun headerCell(text: String): PdfPCell =
        PdfPCell(Phrase(text, LABEL)).apply {
            border = com.lowagie.text.Rectangle.BOTTOM
            paddingBottom = 6f
        }

    private fun bodyCell(
        text: String,
        alignment: Int,
    ): PdfPCell =
        PdfPCell(Phrase(text, BODY)).apply {
            border = com.lowagie.text.Rectangle.NO_BORDER
            horizontalAlignment = alignment
            paddingTop = 4f
            paddingBottom = 4f
        }

    private fun totalCell(text: String): PdfPCell =
        PdfPCell(Phrase(text, LABEL)).apply {
            border = com.lowagie.text.Rectangle.TOP
            horizontalAlignment = Element.ALIGN_RIGHT
            paddingTop = 6f
        }

    /**
     * Minor units to a readable amount. GNF and XAF have no minor unit, so a
     * blanket divide-by-100 would print every Guinean amount a hundred times too
     * small — the kind of error that only shows up on a customer's invoice.
     */
    private fun money(
        minor: Long,
        currency: String,
    ): String =
        if (currency.uppercase() in ZERO_DECIMAL_CURRENCIES) {
            "%,d %s".format(minor, currency.uppercase())
        } else {
            "%,.2f %s".format(BigDecimal(minor).movePointLeft(2), currency.uppercase())
        }

    private fun percent(rate: BigDecimal): String =
        "${rate.movePointRight(2).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}%"

    private companion object {
        const val MARGIN = 42f
        val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        /** ISO 4217 currencies with no minor unit — the platform's own included. */
        val ZERO_DECIMAL_CURRENCIES = setOf("GNF", "XAF", "XOF", "JPY", "KRW", "RWF", "UGX", "VND")

        val TITLE: Font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20f)
        val SUBTITLE: Font = FontFactory.getFont(FontFactory.HELVETICA, 13f)
        val LABEL: Font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9f)
        val BODY: Font = FontFactory.getFont(FontFactory.HELVETICA, 10f)
        val SMALL: Font = FontFactory.getFont(FontFactory.HELVETICA, 8f)
    }
}
