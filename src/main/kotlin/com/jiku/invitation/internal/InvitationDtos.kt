package com.jiku.invitation.internal

import java.time.Instant
import java.util.UUID

data class SendInvitationsResult(
    val queued: Int,
)

data class InvitationStatusResponse(
    val guestId: UUID,
    val channel: String,
    val status: String,
    val attempts: Int,
    val sentAt: Instant?,
)
