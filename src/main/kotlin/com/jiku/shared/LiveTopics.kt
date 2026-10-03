package com.jiku.shared

import java.util.UUID

/**
 * An entity whose writes change a screen people keep open (JIKU-214): the
 * event dashboard, a service's day line, a client's place in it. After each
 * committed insert, update or delete, the live module tells the screens
 * following these topics to reload.
 */
interface LiveScoped {
    fun liveTopics(): Collection<String>
}

/** Names of the topics a screen can follow (JIKU-214). */
object LiveTopics {
    /** An event's dashboard: answers, tickets, check-ins, invitations. */
    fun event(eventId: UUID): String = "event:$eventId"

    /** A service's day line, its pending requests and its clients' places. */
    fun service(serviceId: UUID): String = "service:$serviceId"
}

/**
 * Says the topics of a row changed by a bulk update, which the entity listener
 * cannot see (JIKU-214): an atomic check-in, a counter calling the next person.
 * Heard after commit.
 */
data class LiveChanged(
    val topics: Collection<String>,
)
