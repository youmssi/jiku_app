package com.jiku.catalog.internal

import com.jiku.catalog.ResourceAvailabilityView
import com.jiku.catalog.ResourceModuleApi
import com.jiku.catalog.ResourceType
import com.jiku.catalog.ResourceUnavailabilityView
import com.jiku.catalog.ResourceView
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Ressources et disponibilité (JIKU-84), exposées au reste de l'application par
 * [ResourceModuleApi]. Tenant-scopées par le filtre de persistance : un appel
 * d'un autre tenant ne voit jamais les ressources du premier, et les accès par id
 * partent d'un `findById` déjà filtré (donc introuvable si hors du tenant).
 */
@Service
class ResourceService(
    private val resources: ResourceRepository,
    private val availabilities: ResourceAvailabilityRepository,
    private val unavailabilities: ResourceUnavailabilityRepository,
) : ResourceModuleApi {
    @Transactional(readOnly = true)
    override fun listResources(): List<ResourceView> = resources.findAll().map { it.toView() }

    @Transactional(readOnly = true)
    override fun findResource(resourceId: UUID): ResourceView? = resources.findById(resourceId).map { it.toView() }.orElse(null)

    @Transactional
    override fun createResource(
        name: String,
        type: ResourceType,
        timezone: String,
    ): ResourceView {
        validateTimezone(timezone)
        return resources
            .save(Resource(name = name.trim(), type = type, timezone = timezone))
            .toView()
    }

    @Transactional
    override fun updateResource(
        resourceId: UUID,
        name: String?,
        active: Boolean?,
    ): ResourceView {
        val resource = requireResource(resourceId)
        name?.takeIf { it.isNotBlank() }?.let { resource.name = it.trim() }
        active?.let { resource.active = it }
        return resources.save(resource).toView()
    }

    @Transactional(readOnly = true)
    override fun listAvailability(resourceId: UUID): List<ResourceAvailabilityView> =
        availabilities.findByResourceId(resourceId).map { it.toView() }

    @Transactional
    override fun addAvailability(
        resourceId: UUID,
        dayOfWeek: Int,
        start: LocalTime,
        end: LocalTime,
    ): ResourceAvailabilityView {
        requireResource(resourceId)
        require(dayOfWeek in 1..7) { "dayOfWeek must be 1 (Monday) to 7 (Sunday)" }
        require(start.isBefore(end)) { "start must be before end" }
        return availabilities
            .save(ResourceAvailability(resourceId = resourceId, dayOfWeek = dayOfWeek, start = start, end = end))
            .toView()
    }

    @Transactional
    override fun removeAvailability(
        resourceId: UUID,
        availabilityId: UUID,
    ) {
        val row =
            availabilities.findById(availabilityId).orElseThrow { resourceId.notFound() }
        check(row.resourceId == resourceId) { "availability does not belong to this resource" }
        availabilities.delete(row)
    }

    @Transactional(readOnly = true)
    override fun listUnavailability(resourceId: UUID): List<ResourceUnavailabilityView> =
        unavailabilities.findByResourceId(resourceId).map { it.toView() }

    @Transactional
    override fun addUnavailability(
        resourceId: UUID,
        startsAt: Instant,
        endsAt: Instant,
        reason: String?,
    ): ResourceUnavailabilityView {
        requireResource(resourceId)
        require(endsAt.isAfter(startsAt)) { "endsAt must be after startsAt" }
        return unavailabilities
            .save(ResourceUnavailability(resourceId = resourceId, startsAt = startsAt, endsAt = endsAt, reason = reason))
            .toView()
    }

    @Transactional
    override fun removeUnavailability(
        resourceId: UUID,
        unavailabilityId: UUID,
    ) {
        val row =
            unavailabilities.findById(unavailabilityId).orElseThrow { resourceId.notFound() }
        check(row.resourceId == resourceId) { "unavailability does not belong to this resource" }
        unavailabilities.delete(row)
    }

    @Transactional(readOnly = true)
    override fun isSlotFree(
        resourceId: UUID,
        startsAt: Instant,
        endsAt: Instant,
    ): Boolean {
        val resource = resources.findById(resourceId).orElse(null) ?: return false
        if (!resource.active || !endsAt.isAfter(startsAt)) {
            return false
        }
        // L'indisponibilité prime sur l'horaire : congé, absence, maintenance.
        if (unavailabilities.findByResourceId(resourceId).any { it.startsAt < endsAt && it.endsAt > startsAt }) {
            return false
        }
        // Le créneau doit tenir dans un même jour local et dans une même plage
        // hebdomadaire, exprimées dans le fuseau de la ressource.
        val zone = ZoneId.of(resource.timezone)
        val from = startsAt.atZone(zone)
        val to = endsAt.atZone(zone)
        if (from.toLocalDate() != to.toLocalDate()) {
            return false
        }
        val day = from.dayOfWeek.value
        val slotStart = from.toLocalTime()
        val slotEnd = to.toLocalTime()
        return availabilities.findByResourceId(resourceId).any {
            it.dayOfWeek == day && !it.start.isAfter(slotStart) && !it.end.isBefore(slotEnd)
        }
    }

    private fun requireResource(resourceId: UUID): Resource = resources.findById(resourceId).orElseThrow { resourceId.notFound() }

    private fun validateTimezone(timezone: String) {
        try {
            ZoneId.of(timezone)
        } catch (ex: Exception) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown IANA timezone: $timezone")
        }
    }

    private fun UUID.notFound(): ResponseStatusException = ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found: $this")
}

private fun Resource.toView(): ResourceView =
    ResourceView(
        id = requireNotNull(id),
        name = name,
        type = type,
        timezone = timezone,
        active = active,
    )

private fun ResourceAvailability.toView(): ResourceAvailabilityView =
    ResourceAvailabilityView(
        id = requireNotNull(id),
        resourceId = resourceId,
        dayOfWeek = dayOfWeek,
        start = start,
        end = end,
    )

private fun ResourceUnavailability.toView(): ResourceUnavailabilityView =
    ResourceUnavailabilityView(
        id = requireNotNull(id),
        resourceId = resourceId,
        startsAt = startsAt,
        endsAt = endsAt,
        reason = reason,
    )
