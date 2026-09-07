package com.jiku.catalog.internal

import com.jiku.catalog.ResourceType
import com.jiku.shared.AppointmentBooked
import com.jiku.shared.TenantContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/** Le créneau demandé n'est pas réservable (complet, en attente, ou hors grille). */
class SlotUnavailableException(
    message: String,
) : RuntimeException(message)

data class OpenSlot(
    val startsAt: Instant,
    val endsAt: Instant,
)

data class ReservationOutcome(
    val serviceId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val status: ServiceReservationStatus,
    val resourceIds: List<UUID>,
)

data class ClientBookingOutcome(
    val bookingToken: String,
    val serviceId: UUID,
    val startsAt: Instant,
    val endsAt: Instant,
    val status: ServiceReservationStatus,
)

/**
 * Moteur de créneaux et réservation atomique (JIKU-85), le cœur du produit
 * rendez-vous. Une grille fixe (multiples du pas depuis minuit local au fuseau du
 * service) transforme la disponibilité en comptage : pour chaque case candidate,
 * chaque exigence (type, quantité) doit trouver assez de ressources libres — et
 * une ressource est libre si elle est active, couverte par son horaire
 * hebdomadaire dans SON fuseau, sans indisponibilité, et sans réservation
 * concurrente qui l'occupe.
 *
 * La réservation réclame chaque ressource par un INSERT conditionnel unique
 * (resource_id, starts_at) en HQL — jamais de SQL natif, pour que le prédicat
 * tenant de Hibernate s'applique. Deux clients sur la même case : exactement un
 * INSERT aboutit, l'autre échoue et la transaction se replie. La purge libère les
 * demandes en attente expirées.
 */
@SpringService
class SlotEngine(
    private val services: ServiceRepository,
    private val requirements: ServiceRequirementRepository,
    private val resources: ResourceRepository,
    private val availabilities: ResourceAvailabilityRepository,
    private val unavailabilities: ResourceUnavailabilityRepository,
    private val reservations: ServiceReservationRepository,
    private val configService: ServiceConfigService,
    private val events: ApplicationEventPublisher,
) {
    /** Créneaux du [day] (fuseau du service) satisfaisant chaque exigence. */
    @Transactional(readOnly = true)
    fun openSlots(
        serviceId: UUID,
        day: LocalDate,
        now: Instant = Instant.now(),
    ): List<OpenSlot> {
        val service = service(serviceId) ?: return emptyList()
        val eff = configService.effective(serviceId)
        val step = eff.stepMinutes.toLong()
        val occupancy = eff.occupancyMinutes
        val zone = ZoneId.of(service.timezone)
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val required = requirements.findByServiceId(serviceId)
        if (required.isEmpty()) {
            return emptyList()
        }
        // Horizon maximum : au-delà de aujourd'hui + maxHorizonDays dans le fuseau
        // du service, on ne propose aucun créneau (JIKU-B1).
        val lastAllowedDay = LocalDate.now(zone).plusDays(eff.maxHorizonDays.toLong())
        if (day.isAfter(lastAllowedDay)) {
            return emptyList()
        }

        // Précharge la fenêtre de la journée en quelques requêtes au lieu d'une
        // rafale par case et par exigence : ressources actives des types requis,
        // leurs horaires hebdomadaires, leurs indisponibilités chevauchant le jour,
        // et leurs occupations (confirmées ou en attente non expirée). L'évaluation
        // de chaque case se fait ensuite en mémoire, avec le même prédicat.
        val types = required.map { it.type }.distinct()
        val resourcesOfType = resources.findByActiveTrueAndTypeInOrderByNameAsc(types).groupBy { it.type }
        val resourceIds =
            resourcesOfType.values
                .flatten()
                .mapNotNull { it.id }
                .toSet()
        val availabilitiesByResource =
            availabilities.findByResourceIdIn(resourceIds).groupBy { it.resourceId }
        val unavailabilitiesByResource =
            unavailabilities.findOverlappingByResourceIdIn(resourceIds, dayStart, dayEnd).groupBy { it.resourceId }
        val reservationsByResource =
            reservations.findOccupyingBetweenResources(resourceIds, dayStart, dayEnd, now).groupBy { it.resourceId }

        fun resourceFree(
            resource: Resource,
            startsAt: Instant,
            endsAt: Instant,
        ): Boolean =
            availabilityCovers(resource, startsAt, endsAt, availabilitiesByResource[requireNotNull(resource.id)]) &&
                unavailabilitiesByResource[requireNotNull(resource.id)].orEmpty().none {
                    it.startsAt < endsAt && it.endsAt > startsAt
                } &&
                reservationsByResource[requireNotNull(resource.id)].orEmpty().none {
                    it.startsAt < endsAt && it.endsAt > startsAt
                }

        val minHorizon = now.plusSeconds(eff.minHorizonMinutes * 60L)

        val slots = mutableListOf<OpenSlot>()
        var offset = 0L
        while (true) {
            val startsAt = dayStart.plusSeconds(offset * 60)
            val endsAt = startsAt.plusSeconds(occupancy * 60)
            if (endsAt.isAfter(dayEnd)) break
            if (!startsAt.isBefore(minHorizon)) {
                val freePerType =
                    resourcesOfType.mapValues { (_, resourcesOfType) ->
                        resourcesOfType.count { resourceFree(it, startsAt, endsAt) }
                    }
                if (required.all { freePerType[it.type] ?: 0 >= it.quantity }) {
                    slots += OpenSlot(startsAt, endsAt)
                }
            }
            offset += step
        }
        return slots
    }

    /** Réserve sur demande : la case est bloquée `hold` minutes, puis purgée. */
    @Transactional
    fun reserve(
        serviceId: UUID,
        startsAt: Instant,
    ): ReservationOutcome =
        reserveWithHold(
            serviceId,
            startsAt,
            Instant.now().plusSeconds(configService.effective(serviceId).holdMinutes * 60),
        )

    /** Réserve immédiatement (mode instantané) : la case est confirmée d'emblée. */
    @Transactional
    fun reserveConfirmed(
        serviceId: UUID,
        startsAt: Instant,
    ): ReservationOutcome = doClaim(serviceId, startsAt, heldUntil = null)

    /**
     * Réservation par un client sans compte (JIKU-87). Suit le mode effectif du
     * service : sur demande → PENDING (bloqué jusqu'à expiration), sinon
     * CONFIRMÉ. Remet un jeton client (stocké hashé) qui permet de consulter et
     * d'annuler.
     */
    @Transactional
    fun bookClient(
        serviceId: UUID,
        startsAt: Instant,
        clientName: String,
        clientPhone: String,
    ): ClientBookingOutcome {
        val eff = configService.effective(serviceId)
        val heldUntil =
            if (eff.confirmationMode == ConfirmationMode.ON_REQUEST) {
                Instant.now().plusSeconds(eff.holdMinutes * 60)
            } else {
                null
            }
        val rawToken = BookingToken.new()
        val outcome =
            doClaim(
                serviceId,
                startsAt,
                heldUntil,
                clientName = clientName.trim(),
                clientPhone = clientPhone.trim(),
                bookingTokenHash = BookingToken.hash(rawToken),
            )
        // Une confirmation (mode instantané) matérialise l'invité et son billet
        // dans la même transaction via l'écouteur du module invitation. Une
        // demande en attente (mode sur demande) ne crée AUCUN billet : elle n'est
        // matérialisée qu'au moment où l'organisateur la confirme (JIKU-87/88).
        if (outcome.status == ServiceReservationStatus.CONFIRMED) {
            val tenantId = TenantContext.get() ?: throw SlotUnavailableException("No tenant context")
            val professionalName =
                resources.findByActiveTrueAndTypeOrderByNameAsc(ResourceType.PERSON).firstOrNull()?.name
            events.publishEvent(
                AppointmentBooked(
                    serviceId = serviceId,
                    tenantId = tenantId,
                    startsAt = outcome.startsAt,
                    endsAt = outcome.endsAt,
                    clientName = clientName.trim(),
                    clientPhone = clientPhone.trim(),
                    professionalName = professionalName,
                ),
            )
        }
        return ClientBookingOutcome(
            bookingToken = rawToken,
            serviceId = outcome.serviceId,
            startsAt = outcome.startsAt,
            endsAt = outcome.endsAt,
            status = outcome.status,
        )
    }

    /** Purge les demandes en attente expirées : leurs cases redeviennent réservables. */
    @Transactional
    fun releaseExpiredHolds(now: Instant = Instant.now()): Int = reservations.deleteExpiredHolds(now)

    private fun reserveWithHold(
        serviceId: UUID,
        startsAt: Instant,
        heldUntil: Instant,
    ): ReservationOutcome = doClaim(serviceId, startsAt, heldUntil)

    private fun doClaim(
        serviceId: UUID,
        startsAt: Instant,
        heldUntil: Instant?,
        clientName: String? = null,
        clientPhone: String? = null,
        bookingTokenHash: String? = null,
    ): ReservationOutcome {
        val service = service(serviceId) ?: throw SlotUnavailableException("Service inconnu")
        val eff = configService.effective(serviceId)
        val zone = ZoneId.of(service.timezone)
        val endsAt = startsAt.plusSeconds(eff.occupancyMinutes * 60)
        val now = Instant.now()
        // Purge paresseuse : une demande en attente arrivée à expiration cède sa
        // place avant toute nouvelle tentative. Sans elle, la contrainte d'unicité
        // (resource_id, starts_at) garderait le créneau bloqué pour toujours bien
        // que le comptage l'affiche libre.
        reservations.deleteExpiredHolds(now)
        // Garde d'horizon maximum : on refuse un créneau au-delà de aujourd'hui +
        // maxHorizonDays dans le fuseau du service (JIKU-B1).
        val lastAllowedDay = LocalDate.now(zone).plusDays(eff.maxHorizonDays.toLong())
        if (startsAt.atZone(zone).toLocalDate().isAfter(lastAllowedDay)) {
            throw SlotUnavailableException("Slot is beyond the service booking horizon")
        }
        // Garde de grille : un créneau proposé ailleurs est déjà dans ces bornes.
        if (startsAt.isBefore(now.plusSeconds(eff.minHorizonMinutes * 60L))) {
            throw SlotUnavailableException("Slot is too close to the present moment")
        }
        if (startsAt.atZone(zone).toLocalDate() != endsAt.atZone(zone).toLocalDate()) {
            throw SlotUnavailableException("Slot spans two days")
        }
        val required = requirements.findByServiceId(serviceId)
        if (required.isEmpty()) {
            throw SlotUnavailableException("The service defines no requirements")
        }
        // Ordre stable : on affecte la première ressource libre de chaque type.
        val assigned = mutableListOf<UUID>()
        for (requirement in required.sortedBy { it.type.name }) {
            val free = freeOfType(requirement.type, startsAt, endsAt, now)
            if (free.size < requirement.quantity) {
                throw SlotUnavailableException("Pas assez de ressources libres (${requirement.type})")
            }
            assigned += free.take(requirement.quantity).map { requireNotNull(it.id) }
        }
        val status =
            if (heldUntil == null) ServiceReservationStatus.CONFIRMED else ServiceReservationStatus.PENDING
        for (resourceId in assigned) {
            try {
                reservations.saveAndFlush(
                    ServiceReservation(serviceId = serviceId, resourceId = resourceId, startsAt = startsAt, endsAt = endsAt).apply {
                        this.status = status
                        this.heldUntil = heldUntil
                        this.clientName = clientName
                        this.clientPhone = clientPhone
                        this.bookingTokenHash = bookingTokenHash
                    },
                )
            } catch (ex: DataIntegrityViolationException) {
                // Quelqu'un d'autre a pris la ressource entre le comptage et l'écriture :
                // la transaction entière se replie.
                throw SlotUnavailableException("The slot was just taken on resource $resourceId")
            }
        }
        return ReservationOutcome(
            serviceId = serviceId,
            startsAt = startsAt,
            endsAt = endsAt,
            status = status,
            resourceIds = assigned,
        )
    }

    private fun freeOfType(
        type: ResourceType,
        startsAt: Instant,
        endsAt: Instant,
        now: Instant,
    ): List<Resource> =
        resources.findByActiveTrueAndTypeOrderByNameAsc(type).filter { resource ->
            availabilityCovers(resource, startsAt, endsAt) &&
                unavailabilities.findByResourceId(requireNotNull(resource.id)).none {
                    it.startsAt < endsAt && it.endsAt > startsAt
                } &&
                reservations.countOccupying(requireNotNull(resource.id), startsAt, endsAt, now) == 0L
        }

    private fun availabilityCovers(
        resource: Resource,
        startsAt: Instant,
        endsAt: Instant,
    ): Boolean = availabilityCovers(resource, startsAt, endsAt, availabilities.findByResourceId(requireNotNull(resource.id)))

    private fun availabilityCovers(
        resource: Resource,
        startsAt: Instant,
        endsAt: Instant,
        rows: List<ResourceAvailability>?,
    ): Boolean {
        val zone = ZoneId.of(resource.timezone)
        val from = startsAt.atZone(zone)
        val to = endsAt.atZone(zone)
        if (from.toLocalDate() != to.toLocalDate()) return false
        val day = from.dayOfWeek.value
        val localStart = from.toLocalTime()
        val localEnd = to.toLocalTime()
        return rows.orEmpty().any {
            it.dayOfWeek == day && !it.start.isAfter(localStart) && !it.end.isBefore(localEnd)
        }
    }

    private fun service(serviceId: UUID): Service? = services.findById(serviceId).orElse(null)
}

@Configuration
@EnableConfigurationProperties(SlotGridProperties::class, ServiceDefaultsProperties::class)
class SlotEngineConfiguration
