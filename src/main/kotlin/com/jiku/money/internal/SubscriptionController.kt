package com.jiku.money.internal

import com.jiku.money.ManualPaymentInstructions
import com.jiku.money.SubscriptionRequest
import com.jiku.money.SubscriptionView
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * Abonnement de l'organisateur (JIKU-90) : lecture de la vue (formule, échéance,
 * ressources utilisées/incluses) et demande de prépaiement, qui suit le circuit
 * manuel Mobile Money existant (référence, puis confirmation par le bureau admin).
 */
@RestController
@RequestMapping("/billing/subscription")
@PreAuthorize("hasRole('ORGANIZER')")
class SubscriptionController(
    private val subscriptionService: SubscriptionService,
    private val manualPaymentService: ManualPaymentService,
    private val paymentService: PaymentService,
) {
    @GetMapping
    fun view(): SubscriptionView =
        subscriptionService.view()
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No subscription for this tenant")

    @PostMapping("/request")
    fun request(
        @Valid @RequestBody request: SubscriptionRequest,
    ): ManualPaymentInstructions = manualPaymentService.requestSubscription(request.plan, request.months)

    /** Pays the plan months online with the active provider (JIKU-164). */
    @PostMapping("/checkout")
    fun checkout(
        @Valid @RequestBody request: SubscriptionRequest,
    ): PaymentInitiationResult = paymentService.checkoutSubscription(request.plan, request.months)
}
