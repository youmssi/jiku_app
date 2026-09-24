package com.jiku.tenant.internal

import com.jiku.catalog.ServiceModuleApi
import com.jiku.tenant.TenantModuleApi
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Profil public d'une organisation (la page découverte par identifiant, comme
 * une carte de visite) : nom d'affichage, logo et les services réservables par
 * lien court. Lecture seule ; une organisation suspendue ou sans identifiant
 * reste introuvable (404) plutôt que de fuiter quoi que ce soit.
 */
@RestController
@RequestMapping("/public/orgs")
class PublicOrgController(
    private val tenants: TenantModuleApi,
    private val services: ServiceModuleApi,
) {
    @GetMapping("/{username}")
    fun profile(
        @PathVariable username: String,
    ): PublicOrgProfileView {
        val tenant =
            tenants.findByUsername(username)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "This organization was not found")
        if (tenant.status == "SUSPENDED") {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This organization was not found")
        }
        return PublicOrgProfileView(
            organizationName = tenant.displayName,
            logoUrl = tenant.logoUrl,
            bannerUrl = tenant.bannerUrl,
            primaryColor = tenant.primaryColor,
            services =
                services.publicServiceLinks(tenant.id).map {
                    PublicOrgServiceView(serviceId = it.serviceId, name = it.name, shortCode = it.shortCode)
                },
        )
    }
}

data class PublicOrgProfileView(
    val organizationName: String,
    val logoUrl: String?,
    val bannerUrl: String?,
    val primaryColor: String,
    val services: List<PublicOrgServiceView>,
)

data class PublicOrgServiceView(
    val serviceId: UUID,
    val name: String,
    val shortCode: String,
)
