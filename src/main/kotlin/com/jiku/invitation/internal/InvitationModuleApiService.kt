package com.jiku.invitation.internal

import com.jiku.catalog.InvitationChannel
import com.jiku.invitation.ChannelBreakdown
import com.jiku.invitation.GuestInfo
import com.jiku.invitation.GuestStats
import com.jiku.invitation.InvitationModuleApi
import com.jiku.invitation.SentInvitationCounts
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class InvitationModuleApiService(
    private val guests: GuestRepository,
    private val invitations: InvitationRepository,
) : InvitationModuleApi {
    @Transactional(readOnly = true)
    override fun findGuest(guestId: UUID): GuestInfo? = guests.findById(guestId).map { it.toInfo() }.orElse(null)

    @Transactional(readOnly = true)
    override fun listGuests(eventId: UUID): List<GuestInfo> = guests.findByEventId(eventId).map { it.toInfo() }

    @Transactional(readOnly = true)
    override fun guestCreatedAtInstants(eventId: UUID): List<Instant> = guests.findCreatedAtByEventId(eventId)

    @Transactional(readOnly = true)
    override fun guestStats(eventId: UUID): GuestStats =
        GuestStats(
            total = guests.countByEventIdAndRsvpStatusNot(eventId, RsvpStatus.TRANSFERRED),
            invited = invitations.countInvitedGuests(eventId),
            confirmed = guests.countByEventIdAndRsvpStatus(eventId, RsvpStatus.CONFIRMED),
            declined = guests.countByEventIdAndRsvpStatus(eventId, RsvpStatus.DECLINED),
            pending = guests.countByEventIdAndRsvpStatus(eventId, RsvpStatus.PENDING),
        )

    @Transactional(readOnly = true)
    override fun searchGuests(
        eventId: UUID,
        query: String,
    ): List<GuestInfo> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()
        return guests.search(eventId, "%${term.lowercase()}%").map { it.toInfo() }
    }

    @Transactional(readOnly = true)
    override fun sentInvitationCounts(eventId: UUID): SentInvitationCounts =
        SentInvitationCounts(
            email = invitations.countByEventIdAndChannelAndStatus(eventId, InvitationChannel.EMAIL, InvitationStatus.SENT),
            whatsapp =
                invitations.countByEventIdAndChannelAndStatus(
                    eventId,
                    InvitationChannel.WHATSAPP,
                    InvitationStatus.SENT,
                ),
        )

    @Transactional(readOnly = true)
    override fun channelBreakdown(eventId: UUID): List<ChannelBreakdown> =
        InvitationChannel.entries.map { channel ->
            ChannelBreakdown(
                channel = channel.name,
                sent = invitations.countByEventIdAndChannelAndStatus(eventId, channel, InvitationStatus.SENT),
                failed = invitations.countByEventIdAndChannelAndStatus(eventId, channel, InvitationStatus.FAILED),
                pending = invitations.countByEventIdAndChannelAndStatus(eventId, channel, InvitationStatus.PENDING),
            )
        }
}

private fun Guest.toInfo(): GuestInfo =
    GuestInfo(
        id = requireNotNull(id),
        eventId = eventId,
        firstName = firstName,
        lastName = lastName,
        email = email,
        phoneNumber = phoneNumber,
        rsvpStatus = rsvpStatus.name,
        createdAt = createdAt,
    )
