package com.jiku.invitation.internal

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** A ticket code drawn as a QR code PNG, black on white, the size WhatsApp shows well (JIKU-143). */
object QrCodeImage {
    private const val SIZE = 600
    private const val BLACK = 0x000000
    private const val WHITE = 0xFFFFFF

    fun png(content: String): ByteArray {
        val matrix =
            QRCodeWriter().encode(
                content,
                BarcodeFormat.QR_CODE,
                SIZE,
                SIZE,
                mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (x in 0 until matrix.width) {
            for (y in 0 until matrix.height) {
                image.setRGB(x, y, if (matrix.get(x, y)) BLACK else WHITE)
            }
        }
        return ByteArrayOutputStream().use { out ->
            ImageIO.write(image, "png", out)
            out.toByteArray()
        }
    }
}
