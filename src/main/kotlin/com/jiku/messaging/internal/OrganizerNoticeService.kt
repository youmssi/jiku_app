package com.jiku.messaging.internal

import com.jiku.live.LiveModuleApi
import com.jiku.live.LiveTicket
import com.jiku.shared.LiveChanged
import com.jiku.shared.LiveTopics
import com.jiku.shared.TenantContext
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/** A notice as the bell shows it (JIKU-314). */
data class OrganizerNoticeView(
    val id: UUID,
    val kind: String,
    val params: Map<String, String>,
    val link: String?,
    val createdAt: Instant,
    val readAt: Instant?,
)

data class OrganizerNoticePage(
    val items: List<OrganizerNoticeView>,
    val unread: Long,
    val hasMore: Boolean,
)

data class UnreadNotices(
    val unread: Long,
)

/**
 * The organization's notification bell (JIKU-314): records what the other
 * modules announce, lists it newest first, and keeps track of what was read.
 */
@Service
class OrganizerNoticeService(
    private val notices: OrganizerNoticeRepository,
    private val mapper: ObjectMapper,
    private val live: LiveModuleApi,
    private val events: ApplicationEventPublisher,
) {
    /**
     * Records a notice for the current tenant, in its own transaction: the
     * announcing one has already committed. The caller sets the tenant first,
     * since the session takes it when the transaction opens.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        kind: String,
        params: Map<String, String?>,
        link: String?,
    ) {
        val values = params.filterValues { it != null }.mapValues { requireNotNull(it.value) }
        notices.save(OrganizerNotice(kind = kind, params = mapper.writeValueAsString(values), link = link))
    }

    @Transactional(readOnly = true)
    fun page(
        page: Int,
        size: Int,
    ): OrganizerNoticePage {
        val rows = notices.findAllByOrderByCreatedAtDesc(PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE)))
        return OrganizerNoticePage(
            items = rows.content.map { it.toView() },
            unread = notices.countByReadAtIsNull(),
            hasMore = rows.hasNext(),
        )
    }

    @Transactional(readOnly = true)
    fun unread(): UnreadNotices = UnreadNotices(notices.countByReadAtIsNull())

    @Transactional
    fun markRead(id: UUID): OrganizerNoticeView {
        val row = notices.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Notice not found") }
        if (row.readAt == null) row.readAt = Instant.now()
        return row.toView()
    }

    @Transactional
    fun markAllRead(): UnreadNotices {
        val now = Instant.now()
        val unread = notices.findAllByReadAtIsNull()
        unread.forEach { it.readAt = now }
        if (unread.isNotEmpty()) events.publishEvent(LiveChanged(listOf(LiveTopics.notices(currentTenant()))))
        return UnreadNotices(0)
    }

    fun liveTicket(): LiveTicket = live.ticket(LiveTopics.notices(currentTenant()))

    @Transactional
    fun purgeOlderThan(cutoff: Instant): Int = notices.deleteOlderThan(cutoff)

    private fun currentTenant(): String = requireNotNull(TenantContext.get()) { "The notification bell requires an authenticated tenant" }

    private fun OrganizerNotice.toView(): OrganizerNoticeView =
        OrganizerNoticeView(
            id = requireNotNull(id),
            kind = kind,
            params = mapper.readValue(params, PARAMS),
            link = link,
            createdAt = createdAt,
            readAt = readAt,
        )

    private companion object {
        const val MAX_PAGE = 50
        val PARAMS = object : TypeReference<Map<String, String>>() {}
    }
}
