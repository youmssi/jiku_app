package com.jiku.messaging.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

/**
 * Checks a WhatsApp message while the organizer writes it (JIKU-269): the same
 * word list the send applies, so the screen warns before a message is blocked
 * or billed as marketing. Rules only, no model: same verdict every time, no
 * cost per check.
 */
@RestController
@PreAuthorize("hasRole('ORGANIZER')")
class WhatsAppMessageCheckController(
    private val guard: WhatsAppContentGuard,
) {
    @PostMapping("/messaging/whatsapp/check")
    fun check(
        @Valid @RequestBody request: WhatsAppMessageCheckRequest,
    ): WhatsAppMessageCheck {
        val words = guard.promotionalWords(requireNotNull(request.text))
        return WhatsAppMessageCheck(
            category = if (words.isEmpty()) WhatsAppPricing.CATEGORY_UTILITY else WhatsAppPricing.CATEGORY_MARKETING,
            promotionalWords = words,
        )
    }
}

data class WhatsAppMessageCheckRequest(
    @field:NotNull
    @field:Size(max = 4096)
    val text: String?,
)

data class WhatsAppMessageCheck(
    /** UTILITY when the message reads as information, MARKETING when it would be blocked as promotional. */
    val category: String,
    /** The words that make it promotional, to remove; empty for UTILITY. */
    val promotionalWords: List<String>,
)
