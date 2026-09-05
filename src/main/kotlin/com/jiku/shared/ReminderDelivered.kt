package com.jiku.shared

import java.util.UUID

/**
 * Issue d'un rappel (JIKU-89), renvoyée par le module messaging au module ticket
 * pour clôturer la ligne appointment_reminder : livré (SENT), retenu par une
 * garde-fou (queued, à rejouer au prochain balayage) ou en échec (FAILED).
 */
data class ReminderDelivered(
    val reminderId: UUID,
    val tenantId: String,
    val delivered: Boolean,
    val queued: Boolean,
    val attempts: Int,
    val error: String?,
)
