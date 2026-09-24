package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import com.jiku.shared.ClientCharge
import com.jiku.shared.ServiceDeletedEvent
import com.jiku.shared.TenantCurrency
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * Création et gestion des services d'un organisateur (JIKU-87) : le service et
 * ses exigences en ressources. Tenant-scopé par le filtre de persistance ; la
 * réservation de créneaux et le lien client s'appuient dessus.
 */
@SpringService
class ServiceAdminService(
    private val services: ServiceRepository,
    private val requirements: ServiceRequirementRepository,
    private val reservations: ServiceReservationRepository,
    private val staffLinks: ServiceStaffRepository,
    private val configs: ServiceConfigRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val tenantCurrency: TenantCurrency,
) {
    @Transactional(readOnly = true)
    fun list(): List<ServiceResponse> = services.findAll().map { it.toResponse() }

    @Transactional(readOnly = true)
    fun get(serviceId: UUID): ServiceResponse = services.findById(serviceId).map { it.toResponse() }.orElseThrow { notFound(serviceId) }

    /** What a client of [serviceId] owes the organization, or null when the service is free. */
    @Transactional(readOnly = true)
    fun clientCharge(serviceId: UUID): ClientCharge? = services.findById(serviceId).orElseThrow { notFound(serviceId) }.clientCharge()

    @Transactional
    fun create(request: ServiceCreateRequest): ServiceResponse {
        validateTimezone(request.timezone)
        val service =
            Service(name = request.name.trim(), timezone = request.timezone).apply {
                paymentRule = request.paymentRule
                price = priceFor(request.paymentRule, request.priceMinor, tenantCurrency::ofCurrentTenant)
            }
        return services.save(service).toResponse()
    }

    /**
     * A new price applies to the tickets issued from now on; a ticket already
     * issued keeps the price it was issued at.
     */
    @Transactional
    fun update(
        serviceId: UUID,
        request: ServiceUpdateRequest,
    ): ServiceResponse {
        val service = services.findById(serviceId).orElseThrow { notFound(serviceId) }
        request.name?.takeIf { it.isNotBlank() }?.let { service.name = it.trim() }
        if (request.paymentRule != null || request.priceMinor != null) {
            val rule = request.paymentRule ?: service.paymentRule
            // Switching between paid rules keeps the current amount unless a new one is given.
            val amount = if (rule == PaymentRule.FREE) request.priceMinor else request.priceMinor ?: service.price?.amountMinor
            service.price = priceFor(rule, amount, tenantCurrency::ofCurrentTenant)
            service.paymentRule = rule
        }
        return services.save(service).toResponse()
    }

    @Transactional(readOnly = true)
    fun listRequirements(serviceId: UUID): List<ServiceRequirementResponse> {
        requireExists(serviceId)
        return requirements.findByServiceId(serviceId).map { it.toResponse() }
    }

    @Transactional
    fun addRequirement(
        serviceId: UUID,
        type: ResourceType,
        quantity: Int,
    ): ServiceRequirementResponse {
        requireExists(serviceId)
        if (requirements.findByServiceId(serviceId).any { it.type == type }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This service already requires $type")
        }
        try {
            return requirements
                .save(ServiceRequirement(serviceId = serviceId, type = type, quantity = quantity))
                .toResponse()
        } catch (ex: DataIntegrityViolationException) {
            // Garde de course avec la contrainte d'unicité (service_id, resource_type).
            throw ResponseStatusException(HttpStatus.CONFLICT, "This service already requires $type", ex)
        }
    }

    @Transactional
    fun removeRequirement(
        serviceId: UUID,
        requirementId: UUID,
    ) {
        val row =
            requirements.findById(requirementId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Requirement not found: $requirementId")
            }
        check(row.serviceId == serviceId) { "Requirement does not belong to this service" }
        requirements.delete(row)
    }

    /**
     * Deletes a service and everything that belonged to it. The
     * [ServiceDeletedEvent] is consumed synchronously by the ticketing module
     * inside this same transaction (its appointment tickets, and through the
     * FK cascade their reminders, disappear with the service); the catalog rows
     * (requirements, reservations, staff links, configuration) are removed here.
     */
    @Transactional
    fun delete(serviceId: UUID) {
        val service = services.findById(serviceId).orElseThrow { notFound(serviceId) }
        eventPublisher.publishEvent(ServiceDeletedEvent(requireNotNull(service.id), requireNotNull(service.tenantId)))
        requirements.deleteAll(requirements.findByServiceId(serviceId))
        reservations.deleteAll(reservations.findByServiceId(serviceId))
        staffLinks.deleteAll(staffLinks.findAllByServiceId(serviceId))
        configs.findByServiceId(serviceId)?.let { configs.delete(it) }
        services.delete(service)
    }

    private fun requireExists(serviceId: UUID) {
        services.findById(serviceId).orElseThrow { notFound(serviceId) }
    }

    private fun notFound(serviceId: UUID): ResponseStatusException =
        ResponseStatusException(HttpStatus.NOT_FOUND, "Service not found: $serviceId")

    private fun validateTimezone(timezone: String) {
        try {
            ZoneId.of(timezone)
        } catch (ex: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown IANA timezone: $timezone")
        }
    }
}

private fun Service.toResponse(): ServiceResponse =
    ServiceResponse(
        id = requireNotNull(id),
        name = name,
        timezone = timezone,
        paymentRule = paymentRule,
        priceMinor = price?.amountMinor,
        currency = price?.currency,
    )

private fun ServiceRequirement.toResponse(): ServiceRequirementResponse =
    ServiceRequirementResponse(
        id = requireNotNull(id),
        serviceId = serviceId,
        type = type,
        quantity = quantity,
    )
