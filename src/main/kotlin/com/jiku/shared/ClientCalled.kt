package com.jiku.shared

import java.util.UUID

/**
 * A waiting client was just called (JIKU-114). Published by the catalog's day
 * line once the call is recorded; the messaging module tells the client it is
 * their turn, and at which counter, through the service's client channel.
 */
data class ClientCalled(
    val ticketId: UUID,
    val tenantId: String,
    val clientName: String?,
    val clientPhone: String,
    val counter: String?,
    val channel: ReminderChannel,
)
