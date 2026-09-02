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
                placementEnabled = settings.placementEnabled,
                transferAllowed = settings.transferAllowed,
                transferDeadline = settings.transferDeadline,
                overbookingAllowed = settings.overbookingAllowed,
                maxOverbookingCount = settings.maxOverbookingCount,
            ),
        invitationChannels = invitationChannels.toSet(),
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
    )

fun EventSettingsDto.toEmbeddable(): EventSettings =
    EventSettings(
        placementEnabled = placementEnabled,
        transferAllowed = transferAllowed,
        transferDeadline = transferDeadline,
        overbookingAllowed = overbookingAllowed,
        maxOverbookingCount = maxOverbookingCount,
    )
