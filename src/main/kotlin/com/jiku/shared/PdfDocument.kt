package com.jiku.shared

import com.lowagie.text.Document
import com.lowagie.text.Element
import com.lowagie.text.Font
import com.lowagie.text.FontFactory
import com.lowagie.text.PageSize
import com.lowagie.text.Paragraph
import com.lowagie.text.Phrase
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.PdfPCell
import com.lowagie.text.pdf.PdfPTable
import com.lowagie.text.pdf.PdfWriter
import java.io.ByteArrayOutputStream

/**
 * Primitives de rendu PDF partagées : polices, cellules, tableaux, mise en page.
 *
 * Extrait de `money.InvoiceDocumentRenderer` (JIKU-95) parce qu'un deuxième
 * document — l'attestation de présence — en a besoin, et que `checkin` n'a pas le
 * droit d'atteindre l'interne de `money`. Duplique la mise en page aurait produit
 * deux documents qui divergent visuellement au premier changement.
 *
 * Ce module ne connaît **aucun domaine** : il ne sait ni ce qu'est une facture,
 * ni ce qu'est une présence. Il sait poser du texte sur une page.
 */
object PdfDocument {
    const val MARGIN = 42f

    val TITLE: Font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20f)
    val SUBTITLE: Font = FontFactory.getFont(FontFactory.HELVETICA, 13f)
    val LABEL: Font = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9f)
    val BODY: Font = FontFactory.getFont(FontFactory.HELVETICA, 10f)
    val SMALL: Font = FontFactory.getFont(FontFactory.HELVETICA, 8f)

    /**
     * Construit un PDF A4 et rend ses octets. Le document est toujours fermé,
     * même si [block] échoue : un flux laissé ouvert produit un fichier tronqué
     * que le lecteur accepte silencieusement.
     */
    fun render(block: (Document) -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        val document = Document(PageSize.A4, MARGIN, MARGIN, MARGIN, MARGIN)
        PdfWriter.getInstance(document, output)
        document.open()
        try {
            block(document)
        } finally {
            document.close()
        }
        return output.toByteArray()
    }

    /** Cellule de contenu, sans bordure. */
    fun bodyCell(
        text: String,
        alignment: Int = Element.ALIGN_LEFT,
    ): PdfPCell =
        PdfPCell(Phrase(text, BODY)).apply {
            border = Rectangle.NO_BORDER
            horizontalAlignment = alignment
            paddingTop = 4f
            paddingBottom = 4f
        }

    /** En-tête de colonne, souligné. */
    fun headerCell(text: String): PdfPCell =
        PdfPCell(Phrase(text, LABEL)).apply {
            border = Rectangle.BOTTOM
            paddingBottom = 6f
        }

    /** Cellule de total, surlignée. */
    fun totalCell(
        text: String,
        alignment: Int = Element.ALIGN_RIGHT,
    ): PdfPCell =
        PdfPCell(Phrase(text, LABEL)).apply {
            border = Rectangle.TOP
            horizontalAlignment = alignment
            paddingTop = 6f
        }

    /** Bloc d'identité : un intitulé puis des lignes. */
    fun partyCell(
        label: String,
        rows: List<String>,
    ): PdfPCell =
        PdfPCell().apply {
            border = Rectangle.NO_BORDER
            addElement(Paragraph(label, LABEL))
            rows.forEach { addElement(Paragraph(it, BODY)) }
        }

    /** Tableau pleine largeur, avec espacement après. */
    fun table(
        widths: FloatArray,
        spacingAfter: Float = 12f,
    ): PdfPTable =
        PdfPTable(widths).apply {
            widthPercentage = 100f
            setSpacingAfter(spacingAfter)
        }
}
