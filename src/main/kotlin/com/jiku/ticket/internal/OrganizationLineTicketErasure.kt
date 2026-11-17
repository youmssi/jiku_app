package com.jiku.ticket.internal

import com.jiku.shared.OrganizationClosed
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Removes the clients' names and numbers from the day-line tickets of an
 * organization deleted with its owner's account (JIKU-316); the tickets and
 * their payments stay.
 */
@Component
class OrganizationLineTicketErasure(
    private val jdbc: JdbcTemplate,
) {
    @EventListener
    fun on(event: OrganizationClosed) {
        jdbc.update(
            "UPDATE ticket SET client_name = NULL, client_phone = NULL " +
                "WHERE tenant_id = ? AND (client_name IS NOT NULL OR client_phone IS NOT NULL)",
            event.tenantId,
        )
    }
}
