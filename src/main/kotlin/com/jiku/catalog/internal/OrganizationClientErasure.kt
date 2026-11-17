package com.jiku.catalog.internal

import com.jiku.shared.OrganizationClosed
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * Removes the clients' names and numbers from the appointments of an
 * organization deleted with its owner's account (JIKU-316), as the retention
 * policy does for old appointments; the reservations stay, without anyone in them.
 */
@Component
class OrganizationClientErasure(
    private val jdbc: JdbcTemplate,
) {
    @EventListener
    fun on(event: OrganizationClosed) {
        jdbc.update(
            "UPDATE service_reservation SET client_name = NULL, client_phone = NULL " +
                "WHERE tenant_id = ? AND (client_name IS NOT NULL OR client_phone IS NOT NULL)",
            event.tenantId,
        )
    }
}
