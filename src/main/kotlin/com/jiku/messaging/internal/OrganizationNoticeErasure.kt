package com.jiku.messaging.internal

import com.jiku.shared.OrganizationClosed
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Empties the notification bell of an organization deleted with its owner's
 * account (JIKU-316). Sending records follow their own retention period.
 */
@Component
class OrganizationNoticeErasure(
    private val notices: OrganizerNoticeRepository,
) {
    @EventListener
    fun on(event: OrganizationClosed) {
        notices.deleteAllByTenantId(event.tenantId)
    }
}
