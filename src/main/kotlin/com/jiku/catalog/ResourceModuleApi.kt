package com.jiku.catalog

import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/**
 * Le module `catalog` accueille, à côté des événements, les ressources et leur
 * disponibilité (JIKU-84), conformément à l'architecture cible : tout autre
 * module passe par cette interface et ne touche jamais les tables internes.
 *
 * Les lectures/écritures sont scopées au tenant courant par le filtre de
 * persistance — un autre tenant ne voit ni ne modifie jamais les ressources du
 * premier.
 */
interface ResourceModuleApi {
    fun listResources(): List<ResourceView>

    fun findResource(resourceId: UUID): ResourceView?

    fun createResource(
        name: String,
        type: ResourceType,
        timezone: String,
    ): ResourceView

    /** Renomme et/ou active/désactive une ressource existante. */
    fun updateResource(
        resourceId: UUID,
        name: String? = null,
        active: Boolean? = null,
    ): ResourceView

    fun listAvailability(resourceId: UUID): List<ResourceAvailabilityView>

    fun addAvailability(
        resourceId: UUID,
        dayOfWeek: Int,
        start: LocalTime,
        end: LocalTime,
    ): ResourceAvailabilityView

    fun removeAvailability(
        resourceId: UUID,
        availabilityId: UUID,
    )

    fun listUnavailability(resourceId: UUID): List<ResourceUnavailabilityView>

    fun addUnavailability(
        resourceId: UUID,
        startsAt: Instant,
        endsAt: Instant,
        reason: String? = null,
    ): ResourceUnavailabilityView

    fun removeUnavailability(
        resourceId: UUID,
        unavailabilityId: UUID,
    )

    /**
     * « Libre sur cette case ? » : la ressource existe et est active, le créneau
     * est couvert par un horaire hebdomadaire dans le fuseau de la ressource, et
     * aucune indisponibilité (congé, absence) ne le chevauche.
     */
    fun isSlotFree(
        resourceId: UUID,
        startsAt: Instant,
        endsAt: Instant,
    ): Boolean
}

data class ResourceView(
    val id: UUID,
    val name: String,
    val type: ResourceType,
    val timezone: String,
    val active: Boolean,
)

data class ResourceAvailabilityView(
    val id: UUID,
    val resourceId: UUID,
    val dayOfWeek: Int,
    val start: LocalTime,
    val end: LocalTime,
)

data class ResourceUnavailabilityView(
    val id: UUID,
    val resourceId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val reason: String?,
)
