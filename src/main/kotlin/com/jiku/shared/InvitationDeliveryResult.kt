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
)
