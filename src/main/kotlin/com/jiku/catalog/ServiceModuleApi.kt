package com.jiku.catalog

import java.util.UUID

/**
 * The catalog module's API for cross-module reads of services. Public surfaces
 * (the organization's discoverable profile) list a tenant's bookable services
 * through this interface only, never through catalog internals.
 */
interface ServiceModuleApi {
    /**
     * Les services du tenant qui possèdent un lien court de réservation, prêts
     * à être proposés sur le profil public de l'organisation.
     */
    fun publicServiceLinks(tenantId: UUID): List<PublicServiceLink>
}

/** Un service présentable publiquement : son nom et son lien court de réservation. */
data class PublicServiceLink(
    val serviceId: UUID,
    val name: String,
    val shortCode: String,
)
