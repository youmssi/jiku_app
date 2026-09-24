package com.jiku.catalog.internal

import com.jiku.shared.RandomCode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/**
 * Le lien court d'un service : un code aléatoire lisible remplace le jeton signé
 * dans l'URL partagée. Rien de dérivable dans le code — il est tiré au sort dans
 * un alphabet sans ambiguïté et la ligne est résolue en base par le flux public.
 */
@Entity
@Table(name = "service_link")
class ServiceLink(
    @Column(name = "code", nullable = false, unique = true)
    val code: String,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,
    @Column(name = "service_id", nullable = false)
    val serviceId: UUID,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    var id: UUID? = null
}

interface ServiceLinkRepository : JpaRepository<ServiceLink, UUID> {
    fun findByServiceId(serviceId: UUID): ServiceLink?

    fun findByCode(code: String): ServiceLink?
}

/**
 * Émet et retrouve les codes courts de service. L'alphabet omet 0/O/1/I/L pour
 * qu'un code lu à voix haute ou recopié depuis un téléphone ne se trompe jamais.
 */
@Service
class ServiceLinkCodeService(
    private val links: ServiceLinkRepository,
) {
    fun forService(
        serviceId: UUID,
        tenantId: String,
    ): ServiceLink = links.findByServiceId(serviceId) ?: issue(serviceId, tenantId)

    fun byCode(code: String): ServiceLink? = links.findByCode(code)

    private fun issue(
        serviceId: UUID,
        tenantId: String,
    ): ServiceLink {
        val existing = links.findByServiceId(serviceId)
        if (existing != null) return existing
        // Un code non encore utilisé est garanti par la contrainte unique : on
        // réessaie jusqu'à succès (collision astronomiquement rare).
        var code: String
        do {
            code = RandomCode.generate(CODE_LENGTH)
        } while (links.findByCode(code) != null)
        return links.save(ServiceLink(code = code, tenantId = tenantId, serviceId = serviceId))
    }

    companion object {
        const val CODE_LENGTH = 10
    }
}
