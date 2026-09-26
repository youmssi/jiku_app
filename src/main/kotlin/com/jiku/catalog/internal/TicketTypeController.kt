package com.jiku.catalog.internal

import com.jiku.shared.TenantCurrency
import com.jiku.shared.TicketTypeUsageGate
import com.jiku.shared.VerificationGate
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Catégories d'accès d'un événement (JIKU-93). Tenant-scopé par le filtre de
 * persistance.
 */
@RestController
@RequestMapping("/events/{eventId}/ticket-types")
@PreAuthorize("hasRole('ORGANIZER')")
class TicketTypeController(
    private val service: TicketTypeService,
) {
    @GetMapping
    fun list(
        @PathVariable eventId: UUID,
    ): List<TicketTypeResponse> = service.list(eventId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @PathVariable eventId: UUID,
        @Valid @RequestBody request: UpsertTicketTypeRequest,
    ): TicketTypeResponse = service.create(eventId, request)

    @PutMapping("/{typeId}")
    fun update(
        @PathVariable eventId: UUID,
        @PathVariable typeId: UUID,
        @Valid @RequestBody request: UpsertTicketTypeRequest,
    ): TicketTypeResponse = service.update(eventId, typeId, request)

    @DeleteMapping("/{typeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable eventId: UUID,
        @PathVariable typeId: UUID,
    ) = service.delete(eventId, typeId)
}

@Service
class TicketTypeService(
    private val types: TicketTypeRepository,
    private val events: EventRepository,
    private val guests: TicketTypeUsageGate,
    private val tenantCurrency: TenantCurrency,
    private val verification: VerificationGate,
) {
    @Transactional(readOnly = true)
    fun list(eventId: UUID): List<TicketTypeResponse> = types.findByEventIdOrderByPositionAsc(eventId).map { it.toResponse() }

    @Transactional
    fun create(
        eventId: UUID,
        request: UpsertTicketTypeRequest,
    ): TicketTypeResponse {
        requireEvent(eventId)
        // A priced category makes clients pay: the organization must be verified (référentiel §9).
        if ((request.priceMinor ?: 0) > 0) verification.requireVerified()
        val type =
            TicketType(eventId = eventId, label = request.label.trim(), colorHex = request.colorHex).apply {
                maxCapacity = request.maxCapacity
                position = request.position
                price = request.priceMinor?.let { Price(it, tenantCurrency.ofCurrentTenant()) }
            }
        return types.save(type).toResponse()
    }

    /**
     * Le plafond peut être abaissé sous le nombre déjà confirmé — l'organisateur
     * corrige parfois une erreur de saisie après des confirmations. Le compteur
     * n'est pas touché : refuser d'autres entrées est le bon comportement,
     * réécrire l'histoire ne l'est pas.
     *
     * Le prix, lui, est figé dès qu'un billet est confirmé : un même billet ne
     * peut pas avoir coûté deux montants différents selon la date d'achat.
     */
    @Transactional
    fun update(
        eventId: UUID,
        typeId: UUID,
        request: UpsertTicketTypeRequest,
    ): TicketTypeResponse {
        val type = load(eventId, typeId)
        if (type.confirmedCount > 0 && type.price?.amountMinor != request.priceMinor) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The price of a category cannot change once tickets are confirmed")
        }
        if ((request.priceMinor ?: 0) > 0 && request.priceMinor != type.price?.amountMinor) verification.requireVerified()
        type.price = request.priceMinor?.let { Price(it, tenantCurrency.ofCurrentTenant()) }
        type.label = request.label.trim()
        type.colorHex = request.colorHex
        type.maxCapacity = request.maxCapacity
        type.position = request.position
        return types.save(type).toResponse()
    }

    /**
     * Supprimer une catégorie à laquelle des invités sont rattachés les laisserait
     * sans catégorie sur un billet déjà émis, et le portier ne saurait plus où les
     * admettre. On refuse en disant combien sont concernés.
     */
    @Transactional
    fun delete(
        eventId: UUID,
        typeId: UUID,
    ) {
        val type = load(eventId, typeId)
        val rattaches = guests.countGuestsWithTicketType(typeId)
        if (rattaches > 0) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot delete a category that still has guests assigned; move them first ($rattaches affected)",
            )
        }
        types.delete(type)
    }

    private fun requireEvent(eventId: UUID) {
        if (!events.existsById(eventId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        }
    }

    private fun load(
        eventId: UUID,
        typeId: UUID,
    ): TicketType =
        types.findById(typeId).orElse(null)?.takeIf { it.eventId == eventId }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found")

    private fun TicketType.toResponse() =
        TicketTypeResponse(
            id = requireNotNull(id),
            label = label,
            colorHex = colorHex,
            maxCapacity = maxCapacity,
            confirmedCount = confirmedCount,
            position = position,
            priceMinor = price?.amountMinor,
            currency = price?.currency,
        )
}
