package com.jiku.backoffice.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.http.HttpStatus
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

/**
 * Capture publique des professionnels intéressés par la prise de rendez-vous
 * (JIKU-98).
 *
 * La campagne d'acquisition tourne avant que le produit n'existe : sans ce point
 * d'entrée, chaque visiteur intéressé est perdu et la dépense publicitaire ne
 * produit rien de réutilisable. Le formulaire ne promet aucune disponibilité
 * immédiate — c'est une liste d'accès anticipé, pas une réservation.
 *
 * Non authentifié, donc soumis à une politique de limitation dédiée
 * (`api.rate-limit.policies.prospect`).
 */
@RestController
@RequestMapping("/prospects")
class ProspectLeadController(
    private val service: ProspectLeadService,
) {
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody request: ProspectLeadRequest,
    ): ProspectLeadAck = service.register(request)
}

/** Bureau d'administration : la liste des pistes, pour rappeler dans l'ordre. */
@RestController
@RequestMapping("/admin/prospects")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminProspectLeadController(
    private val service: ProspectLeadService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
    ): List<ProspectLeadView> = service.list(status)

    @PostMapping("/{id}/contacted")
    fun markContacted(
        @PathVariable id: UUID,
    ): ProspectLeadView = service.markContacted(id)
}

@Service
class ProspectLeadService(
    private val leads: ProspectLeadRepository,
) {
    private val log = LoggerFactory.getLogger(ProspectLeadService::class.java)

    /**
     * Enregistre une piste. Idempotent par téléphone : un professionnel qui soumet
     * deux fois met à jour ses informations plutôt que de créer un doublon que
     * quelqu'un devra dédupliquer à la main.
     */
    @Transactional
    fun register(request: ProspectLeadRequest): ProspectLeadAck {
        val sector = parseSector(request.sector)
        val phone = request.phone.trim()

        val lead =
            leads.findByPhone(phone)?.apply {
                businessName = request.businessName.trim()
                contactName = request.contactName.trim()
                this.sector = sector
                email = request.email?.trim()?.ifBlank { null }
                city = request.city?.trim()?.ifBlank { null }
                weeklyVolume = request.weeklyVolume?.trim()?.ifBlank { null }
                note = request.note?.trim()?.ifBlank { null }
                source = request.source?.trim()?.ifBlank { null } ?: source
            } ?: ProspectLead(
                businessName = request.businessName.trim(),
                contactName = request.contactName.trim(),
                phone = phone,
                sector = sector,
            ).apply {
                email = request.email?.trim()?.ifBlank { null }
                city = request.city?.trim()?.ifBlank { null }
                weeklyVolume = request.weeklyVolume?.trim()?.ifBlank { null }
                note = request.note?.trim()?.ifBlank { null }
                source = request.source?.trim()?.ifBlank { null }
            }

        val saved = leads.save(lead)
        log.info("Prospect lead registered: sector={} source={}", saved.sector, saved.source ?: "-")
        return ProspectLeadAck(id = requireNotNull(saved.id))
    }

    fun list(status: String?): List<ProspectLeadView> {
        val all = leads.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
        val filtered = status?.let { s -> all.filter { it.status.name.equals(s, ignoreCase = true) } } ?: all
        return filtered.map { it.toView() }
    }

    @Transactional
    fun markContacted(id: UUID): ProspectLeadView {
        val lead =
            leads.findById(id).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Lead not found")
            }
        lead.status = ProspectStatus.CONTACTED
        lead.contactedAt = Instant.now()
        return leads.save(lead).toView()
    }

    private fun parseSector(raw: String): ProspectSector =
        runCatching { ProspectSector.valueOf(raw.uppercase()) }
            .getOrElse { ProspectSector.AUTRE }

    private fun ProspectLead.toView() =
        ProspectLeadView(
            id = requireNotNull(id),
            businessName = businessName,
            contactName = contactName,
            phone = phone,
            email = email,
            sector = sector.name,
            city = city,
            weeklyVolume = weeklyVolume,
            note = note,
            source = source,
            status = status.name,
            createdAt = createdAt,
            contactedAt = contactedAt,
        )
}

interface ProspectLeadRepository : JpaRepository<ProspectLead, UUID> {
    fun findByPhone(phone: String): ProspectLead?
}

/**
 * Seuls quatre champs sont exigés. Chaque champ supplémentaire obligatoire réduit
 * le taux de conversion, et une piste incomplète vaut mieux qu'une piste perdue.
 */
data class ProspectLeadRequest(
    @field:NotBlank
    @field:Size(max = 255)
    val businessName: String,
    @field:NotBlank
    @field:Size(max = 255)
    val contactName: String,
    @field:NotBlank
    @field:Size(max = 32)
    val phone: String,
    @field:NotBlank
    val sector: String,
    @field:Email
    @field:Size(max = 255)
    val email: String? = null,
    @field:Size(max = 120)
    val city: String? = null,
    @field:Size(max = 20)
    val weeklyVolume: String? = null,
    @field:Size(max = 1000)
    val note: String? = null,
    @field:Size(max = 100)
    val source: String? = null,
)

/** Accusé minimal : le formulaire public n'a pas besoin d'en savoir plus. */
data class ProspectLeadAck(
    val id: UUID,
)

data class ProspectLeadView(
    val id: UUID,
    val businessName: String,
    val contactName: String,
    val phone: String,
    val email: String?,
    val sector: String,
    val city: String?,
    val weeklyVolume: String?,
    val note: String?,
    val source: String?,
    val status: String,
    val createdAt: Instant,
    val contactedAt: Instant?,
)
