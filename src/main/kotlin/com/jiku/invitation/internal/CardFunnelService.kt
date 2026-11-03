package com.jiku.invitation.internal

import com.jiku.catalog.EventModuleApi
import com.jiku.shared.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 * Counts where a card's answers come from (JIKU-282): shares made by the
 * organizer, visits of the card page and answers, per network. Only numbers
 * are kept, so the organizer learns where to share without anyone being
 * tracked.
 */
@Service
class CardFunnelService(
    private val counters: CardFunnelRepository,
    private val openInvitations: OpenInvitationRepository,
    private val responses: OpenResponseRepository,
    private val events: EventModuleApi,
) {
    /** The organizer shared the card through [source]. */
    @Transactional
    fun recordShare(
        eventId: UUID,
        source: CardSource,
    ) {
        requireCard(eventId)
        count(eventId, source) { counters.addShare(eventId, it) }
    }

    /** Someone opened the card page from a link tagged [source]; runs under the card's tenant. */
    @Transactional
    fun recordVisit(
        eventId: UUID,
        source: CardSource,
    ) {
        count(eventId, source) { counters.addVisit(eventId, it) }
    }

    /** One row per network that saw any activity, the most answers first. */
    @Transactional(readOnly = true)
    fun funnel(eventId: UUID): List<CardFunnelRow> {
        requireCard(eventId)
        val counted = counters.findByEventId(eventId).associateBy { it.source }
        val answered =
            responses
                .findByEventIdOrderByUpdatedAtDesc(eventId)
                .filter { it.removedAt == null }
                .groupBy { CardSource.of(it.source).tag }
        return (counted.keys + answered.keys)
            .map { source ->
                val answers = answered[source].orEmpty()
                CardFunnelRow(
                    source = source,
                    shares = counted[source]?.shares ?: 0,
                    visits = counted[source]?.visits ?: 0,
                    answers = answers.size.toLong(),
                    coming = answers.filter { it.answer == OpenAnswer.YES }.sumOf { it.heads }.toLong(),
                )
            }.sortedWith(compareByDescending<CardFunnelRow> { it.answers }.thenByDescending { it.visits }.thenBy { it.source })
    }

    private fun count(
        eventId: UUID,
        source: CardSource,
        increment: (String) -> Unit,
    ) {
        val tenantId = TenantContext.get() ?: error("A card's counts need its tenant")
        counters.ensure(tenantId, eventId, source.tag)
        increment(source.tag)
    }

    private fun requireCard(eventId: UUID) {
        events.findEvent(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        openInvitations.findByEventId(eventId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No open invitation")
    }
}
