package com.jiku.money.internal

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Receives the Mobile Money provider's server-to-server payment confirmation
 * (JIKU-33). Public (the caller is the provider, not a user session) and
 * authenticated by the signature the [PaymentProvider] verifies over the raw body;
 * an unverifiable payload is rejected with 401 and nothing is unlocked.
 */
@RestController
@RequestMapping("/billing/payments/callback")
class PaymentCallbackController(
    private val paymentService: PaymentService,
) {
    @PostMapping
    fun callback(
        @RequestHeader(name = "X-Signature", required = false) signature: String?,
        @RequestBody rawBody: String,
    ): ResponseEntity<Void> =
        when (paymentService.handleCallback(rawBody, signature)) {
            PaymentService.CallbackOutcome.INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
            PaymentService.CallbackOutcome.UNKNOWN -> ResponseEntity.notFound().build()
            else -> ResponseEntity.ok().build()
        }
}
