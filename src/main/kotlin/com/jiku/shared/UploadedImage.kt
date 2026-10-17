package com.jiku.shared

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** The size rules an uploaded photo is held to. */
data class ImageRules(
    val maxBytes: Int,
    val minWidth: Int,
    val minHeight: Int,
)

/**
 * Checks that [content] is a real JPEG or PNG photo within [rules] and returns
 * its media type. Shared by every place an organizer uploads a public photo
 * (an event's banner, the organization's logo and banner), so they refuse the
 * same files with the same answers.
 */
fun acceptUploadedImage(
    content: ByteArray,
    contentType: String?,
    rules: ImageRules,
): String {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase()
    if (type == null || type !in ACCEPTED_IMAGE_TYPES) {
        throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "The photo must be a JPEG or PNG image")
    }
    if (content.isEmpty() || content.size > rules.maxBytes) {
        throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "The photo must be at most ${rules.maxBytes} bytes")
    }
    val image =
        runCatching { ImageIO.read(ByteArrayInputStream(content)) }.getOrNull()
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This file is not a readable image")
    if (image.width < rules.minWidth || image.height < rules.minHeight) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "The photo must be at least ${rules.minWidth} × ${rules.minHeight} pixels",
        )
    }
    return type
}

private val ACCEPTED_IMAGE_TYPES = setOf("image/jpeg", "image/png")
