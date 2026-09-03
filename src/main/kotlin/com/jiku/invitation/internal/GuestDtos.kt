package com.jiku.invitation.internal

import java.time.Instant
import java.util.UUID

data class RowIssue(
    val row: Int,
    val reason: String,
)

data class GuestImportResult(
    val imported: Int,
    val skippedDuplicates: Int,
    val failed: Int,
    val failures: List<RowIssue>,
    val warnings: List<RowIssue>,
)

data class GuestResponse(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val phoneNumber: String?,
    val excludedFromInvitations: Boolean,
    /**
     * Heure d'entrée, si la personne est venue (JIKU-95). Null sinon — c'est ce
     * qui décide si une attestation de présence peut être délivrée.
     */
    val checkedInAt: Instant? = null,
    /** Catégorie d'accès de l'invité, si l'événement en définit (JIKU-93). */
    val ticketTypeId: UUID? = null,
)

data class SetGuestExclusionRequest(
    val excluded: Boolean,
)

/**
 * Rattache un invité à une catégorie d'accès, ou l'en détache (`null`). JIKU-93.
 */
data class SetGuestTicketTypeRequest(
    val ticketTypeId: UUID?,
)
