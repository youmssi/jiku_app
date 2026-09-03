package com.jiku.invitation.internal

import com.jiku.shared.TicketTypeUsageGate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Fournit à `catalog` le nombre d'invités rattachés à une catégorie (JIKU-93),
 * sans qu'il touche la table des invités. Même patron d'adaptateur que
 * `UsageAllowanceGateAdapter`.
 */
@Component
class TicketTypeUsageGateAdapter(
    private val guests: GuestRepository,
) : TicketTypeUsageGate {
    override fun countGuestsWithTicketType(ticketTypeId: UUID): Long = guests.countByTicketTypeId(ticketTypeId)
}
