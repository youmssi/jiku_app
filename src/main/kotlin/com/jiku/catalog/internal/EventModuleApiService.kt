package com.jiku.catalog.internal

import com.jiku.catalog.EventInfo
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.EventSummary
import com.jiku.catalog.QuestionInfo
import com.jiku.catalog.QuorumInfo
import com.jiku.catalog.RetentionCandidate
import com.jiku.catalog.TicketTypeInfo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class EventModuleApiService(
    private val events: EventRepository,
    private val eventService: EventService,
    private val ticketTypes: TicketTypeRepository,
    private val questions: EventQuestionRepository,
) : EventModuleApi {
    @Transactional(readOnly = true)
    override fun findEvent(eventId: UUID): EventInfo? = events.findById(eventId).map { it.toEventInfo() }.orElse(null)

    @Transactional(readOnly = true)
    override fun eventsPastRetention(cutoff: Instant): List<RetentionCandidate> =
        events.findEventsPastRetention(cutoff).map {
            RetentionCandidate(eventId = UUID.fromString(it[0].toString()), tenantId = it[1].toString())
        }

    @Transactional
    override fun reserveAttendanceSlot(eventId: UUID): Boolean {
        val event = events.findById(eventId).orElse(null) ?: return false
        val limit =
            event.maxCapacity?.let { capacity ->
                capacity + if (event.settings.overbookingAllowed) (event.settings.maxOverbookingCount ?: 0) else 0
            } ?: Int.MAX_VALUE
        return events.reserveSlot(eventId, limit) == 1
    }

    /**
     * Les deux plafonds sont vérifiés dans **une seule transaction**. Si la
     * catégorie est pleine, la place globale déjà prise est rendue en annulant :
     * consommer une place globale sans place de catégorie ferait mentir le
     * compteur, et le portier refuserait quelqu'un que le système croit admis.
     */
    @Transactional
    override fun reserveAttendanceSlot(
        eventId: UUID,
        ticketTypeId: UUID?,
    ): Boolean {
        if (ticketTypeId == null) {
            return reserveAttendanceSlot(eventId)
        }
        if (!reserveAttendanceSlot(eventId)) {
            return false
        }
        if (ticketTypes.reserveSlot(ticketTypeId) == 1) {
            return true
        }
        // La catégorie est pleine : on rend la place globale plutôt que de la
        // laisser consommée pour rien.
        events.releaseSlot(eventId)
        return false
    }

    @Transactional
    override fun releaseAttendanceSlot(eventId: UUID) {
        events.releaseSlot(eventId)
    }

    @Transactional
    override fun releaseAttendanceSlot(
        eventId: UUID,
        ticketTypeId: UUID?,
    ) {
        events.releaseSlot(eventId)
        ticketTypeId?.let { ticketTypes.releaseSlot(it) }
    }

    /**
     * L'ordre compte : on prend d'abord la place d'arrivée, on ne rend l'ancienne
     * qu'une fois la nouvelle acquise. L'inverse ouvrirait une fenêtre pendant
     * laquelle la place libérée peut être prise par quelqu'un d'autre, laissant
     * l'invité déplacé sans catégorie ni moyen de revenir dans la sienne.
     */
    @Transactional
    override fun moveTicketTypeSlot(
        from: UUID?,
        to: UUID?,
    ): Boolean {
        if (from == to) return true
        if (to != null && ticketTypes.reserveSlot(to) != 1) return false
        from?.let { ticketTypes.releaseSlot(it) }
        return true
    }

    @Transactional(readOnly = true)
    override fun ticketTypes(eventId: UUID): List<TicketTypeInfo> =
        ticketTypes.findByEventIdOrderByPositionAsc(eventId).map {
            TicketTypeInfo(
                id = requireNotNull(it.id),
                label = it.label,
                colorHex = it.colorHex,
                maxCapacity = it.maxCapacity,
                confirmedCount = it.confirmedCount,
                priceMinor = it.price?.amountMinor,
                currency = it.price?.currency,
            )
        }

    @Transactional(readOnly = true)
    override fun eventQuestions(eventId: UUID): List<QuestionInfo> =
        questions.findByEventIdOrderByPositionAsc(eventId).map {
            QuestionInfo(
                id = requireNotNull(it.id),
                prompt = it.prompt,
                required = it.required,
            )
        }

    @Transactional(readOnly = true)
    override fun quorum(
        eventId: UUID,
        totalGuests: Long,
        checkedIn: Long,
    ): QuorumInfo? {
        val event = events.findById(eventId).orElse(null) ?: return null
        val quorum = event.quorum ?: return null
        if (!quorum.isConfigured()) {
            return null
        }
        val required = quorum.requiredFor(totalGuests) ?: return null
        return QuorumInfo(
            required = required,
            current = checkedIn,
            reached = checkedIn >= required,
            reachedAt = quorum.reachedAt,
        )
    }

    @Transactional
    override fun markQuorumReached(eventId: UUID) {
        events.markQuorumReached(eventId, Instant.now())
    }

    @Transactional(readOnly = true)
    override fun adminSearchEvents(
        tenantId: UUID,
        query: String?,
        limit: Int,
    ): List<EventSummary> =
        events
            .adminSearchByTenant(
                tenantId = tenantId.toString(),
                query = query?.trim()?.takeIf { it.isNotEmpty() },
                limit = limit.coerceIn(1, MAX_SEARCH_RESULTS),
            ).map { row ->
                EventSummary(
                    id = UUID.fromString(row[0].toString()),
                    name = row[1].toString(),
                    startDateTime = row[2]?.let { toInstant(it) },
                    status = row[3].toString(),
                )
            }

    @Transactional(readOnly = true)
    override fun adminEventNames(eventIds: Collection<UUID>): Map<UUID, String> {
        if (eventIds.isEmpty()) return emptyMap()
        return events
            .findNamesByIds(eventIds.distinct())
            .associate { row -> UUID.fromString(row[0].toString()) to row[1].toString() }
    }

    /** Native-query timestamp columns surface as [java.sql.Timestamp] or [Instant] depending on the JDBC driver. */
    private fun toInstant(value: Any): Instant =
        when (value) {
            is Instant -> value
            is java.sql.Timestamp -> value.toInstant()
            else -> Instant.parse(value.toString())
        }

    private companion object {
        const val MAX_SEARCH_RESULTS = 50
    }
}
