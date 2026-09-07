package com.jiku.catalog.internal

import com.jiku.catalog.EventInfo

fun Event.toResponse(): EventResponse =
    EventResponse(
        id = requireNotNull(id),
        name = name,
        description = description,
        startDateTime = startDateTime,
        endDateTime = endDateTime,
        timezone = timezone,
        location = location,
        maxCapacity = maxCapacity,
        status = status.name,
        settings =
            EventSettingsDto(
                transferAllowed = settings.transferAllowed,
                transferDeadline = settings.transferDeadline,
                overbookingAllowed = settings.overbookingAllowed,
                maxOverbookingCount = settings.maxOverbookingCount,
            ),
        invitationChannels = invitationChannels.toSet(),
        // Null quand aucun quorum n'est configuré, ce qui est le cas de la
        // quasi-totalité des événements : un mariage n'a pas de quorum.
        quorum =
            quorum?.takeIf { it.isConfigured() }?.let {
                QuorumResponse(
                    mode = it.mode?.name ?: QuorumMode.NONE.name,
                    numerator = it.numerator,
                    denominator = it.denominator,
                    absolute = it.absolute,
                    reachedAt = it.reachedAt,
                )
            },
    )

fun Event.toEventInfo(): EventInfo =
    EventInfo(
        id = requireNotNull(id),
        tenantId = requireNotNull(tenantId),
        name = name,
        status = status.name,
        startDateTime = startDateTime,
        endDateTime = endDateTime,
        timezone = timezone,
        location = location,
        transferAllowed = settings.transferAllowed,
        transferDeadline = settings.transferDeadline,
        invitationChannels = invitationChannels.toSet(),
    )

fun EventSettingsDto.toEmbeddable(): EventSettings =
    EventSettings(
        transferAllowed = transferAllowed,
        transferDeadline = transferDeadline,
        overbookingAllowed = overbookingAllowed,
        maxOverbookingCount = maxOverbookingCount,
    )
