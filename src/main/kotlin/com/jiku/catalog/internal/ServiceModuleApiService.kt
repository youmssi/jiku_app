package com.jiku.catalog.internal

import com.jiku.catalog.PublicServiceLink
import com.jiku.catalog.ServiceModuleApi
import com.jiku.shared.TenantContext
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Expose les services d'un tenant aux autres modules (profil public de
 * l'organisation) sans jamais laisser pointer vers les tables du catalogue.
 */
@Service
class ServiceModuleApiService(
    private val services: ServiceRepository,
    private val serviceLinks: ServiceLinkRepository,
) : ServiceModuleApi {
    override fun publicServiceLinks(tenantId: UUID): List<PublicServiceLink> =
        TenantContext.withTenant(tenantId.toString()) {
            services
                .findAll()
                .map { service ->
                    PublicServiceLink(
                        serviceId = requireNotNull(service.id),
                        name = service.name,
                        shortCode = serviceLinks.findByServiceId(requireNotNull(service.id))?.code.orEmpty(),
                    )
                }.filter { it.shortCode.isNotBlank() }
        }
}
