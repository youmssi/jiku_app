package com.jiku.money.internal

import com.jiku.shared.MoneyFormat
import com.jiku.shared.PdfDocument
import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.format.DateTimeFormatter

/**
 * Renders an [Invoice] as a PDF (JIKU-69), its labels in French then English
 * (JIKU-270): French is the official language where Jikū bills, and English
 * serves a buyer elsewhere, without one document per language.
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
        val document = Document(PageSize.A4, PdfDocument.MARGIN, PdfDocument.MARGIN, PdfDocument.MARGIN, PdfDocument.MARGIN)
        PdfWriter.getInstance(document, output)
        document.open()
        try {
            document.add(heading(invoice))
            document.add(parties(invoice))
            document.add(lines(invoice))
            document.add(totals(invoice))
            invoice.correctedInvoiceId?.let {
                document.add(
                    Paragraph("Cet avoir corrige la facture $it. · This credit note corrects invoice $it.", PdfDocument.SMALL).apply {
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
        val title = if (invoice.documentType == DocumentType.CREDIT_NOTE) "Avoir · Credit note" else "Facture · Invoice"
        return Paragraph().apply {
            add(Paragraph(title, PdfDocument.TITLE))
            add(Paragraph(invoice.invoiceNumber, PdfDocument.SUBTITLE))
            add(Paragraph("Émise le · Issued ${invoice.issueDate.format(DATE)}", PdfDocument.SMALL))
            spacingAfter = 18f
        }
    }

    private fun parties(invoice: Invoice): PdfPTable =
        PdfPTable(2).apply {
            widthPercentage = 100f
            setSpacingAfter(18f)
            addCell(
                PdfDocument.partyCell(
                    "Vendeur · From",
                    listOfNotNull(invoice.sellerName, invoice.sellerAddressLine, taxLine(invoice.sellerTaxIdentifier)),
                ),
            )
            addCell(
                PdfDocument.partyCell(
                    "Client · Billed to",
                    listOfNotNull(
                        invoice.buyerLegalName,
                        invoice.buyerAddressLine,
                        "${invoice.buyerCity}, ${invoice.buyerCountry}",
                        invoice.buyerRegistrationNumber?.let { "N° d'immatriculation · Reg. $it" },
                        taxLine(invoice.buyerTaxIdentifier),
                    ),
                ),
            )
        }

    private fun taxLine(identifier: String?): String? = identifier?.let { "N° fiscal · Tax ID $it" }

    private fun lines(invoice: Invoice): PdfPTable =
        PdfPTable(floatArrayOf(5f, 1f, 2f, 2f)).apply {
            widthPercentage = 100f
            setSpacingAfter(12f)
            listOf("Désignation · Description", "Qté · Qty", "Prix unitaire · Unit price", "Montant · Amount").forEach {
                addCell(PdfDocument.headerCell(it))
            }
            invoice.lines.forEach { line ->
                addCell(PdfDocument.bodyCell(line.description, Element.ALIGN_LEFT))
                addCell(PdfDocument.bodyCell(line.quantity.toString(), Element.ALIGN_RIGHT))
                addCell(PdfDocument.bodyCell(MoneyFormat.format(line.unitPriceMinor, invoice.currency), Element.ALIGN_RIGHT))
                addCell(PdfDocument.bodyCell(MoneyFormat.format(line.lineTotalMinor, invoice.currency), Element.ALIGN_RIGHT))
            }
        }

    private fun totals(invoice: Invoice): PdfPTable =
        PdfPTable(floatArrayOf(6f, 3f)).apply {
            widthPercentage = 100f
            horizontalAlignment = Element.ALIGN_RIGHT
            addCell(PdfDocument.bodyCell("Total HT · Total excl. tax", Element.ALIGN_RIGHT))
            addCell(PdfDocument.bodyCell(MoneyFormat.format(invoice.subtotalMinor, invoice.currency), Element.ALIGN_RIGHT))
            // The tax row is printed even at zero so the document never leaves a
            // reader guessing whether tax was considered.
            addCell(
                PdfDocument.bodyCell("${invoice.taxLabel ?: "TVA (${percent(invoice.taxRate)})"} incluse · included", Element.ALIGN_RIGHT),
            )
            addCell(PdfDocument.bodyCell(MoneyFormat.format(invoice.taxAmountMinor, invoice.currency), Element.ALIGN_RIGHT))
            addCell(PdfDocument.totalCell("Total TTC · Total incl. tax"))
            addCell(PdfDocument.totalCell(MoneyFormat.format(invoice.totalMinor, invoice.currency)))
        }

    private fun percent(rate: BigDecimal): String =
        "${rate.movePointRight(2).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()}%"

    private companion object {
        val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }
}
