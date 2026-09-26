package com.jiku.invitation.internal

import com.jiku.ticket.TicketPaymentStatus
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
    /** The guest's answer to the invitation. */
    val rsvpStatus: RsvpStatus = RsvpStatus.PENDING,
    /** The code of the guest's live ticket, once they confirmed; null otherwise. */
    val ticketCode: String? = null,
    /** What the live ticket owes the organization (JIKU-110); null without a ticket. */
    val paymentStatus: TicketPaymentStatus? = null,
    val amountDueMinor: Long? = null,
    val amountDueCurrency: String? = null,
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
