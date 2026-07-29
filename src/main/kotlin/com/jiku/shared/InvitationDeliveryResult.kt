package com.jiku.shared

import java.util.UUID

/**
 * Published by the notification module after attempting to deliver a
 * [GuestInvitedEvent], and consumed by the invitation module to update its
 * invitation's status. Closes the event-driven loop without either module
 * depending on the other.
 */
data class InvitationDeliveryResult(
    val invitationId: UUID,
    val tenantId: String,
    val delivered: Boolean,
    val attempts: Int,
    val error: String?,
    /**
     * True when delivery was deliberately withheld by a capacity guardrail (JIKU-61,
     * e.g. the WhatsApp 24h conversation-window safety threshold) rather than having
     * failed — the invitation should be retried automatically once capacity frees up,
     * not treated as a terminal failure.
     */
    val queued: Boolean = false,
)
