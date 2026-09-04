package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
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
) {
    @Transactional(readOnly = true)
    fun list(): List<ServiceResponse> = services.findAll().map { it.toResponse() }

    @Transactional(readOnly = true)
    fun get(serviceId: UUID): ServiceResponse = services.findById(serviceId).map { it.toResponse() }.orElseThrow { notFound(serviceId) }

    @Transactional
    fun create(
        name: String,
        timezone: String,
    ): ServiceResponse {
        validateTimezone(timezone)
        return services
            .save(Service(name = name.trim(), timezone = timezone))
            .toResponse()
    }

    @Transactional
    fun updateName(
        serviceId: UUID,
        name: String?,
    ): ServiceResponse {
        val service = services.findById(serviceId).orElseThrow { notFound(serviceId) }
        name?.takeIf { it.isNotBlank() }?.let { service.name = it.trim() }
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

private fun Service.toResponse(): ServiceResponse = ServiceResponse(id = requireNotNull(id), name = name, timezone = timezone)

private fun ServiceRequirement.toResponse(): ServiceRequirementResponse =
    ServiceRequirementResponse(
        id = requireNotNull(id),
        serviceId = serviceId,
        type = type,
        quantity = quantity,
    )
