package com.jiku.live

import java.time.Instant

/**
 * Lets a screen follow a topic (JIKU-214). The module that owns a screen checks
 * the caller may see it, then asks here for a ticket; the browser opens
 * `GET /live/stream?ticket=…` with it. The stream carries no data, only "this
 * changed": the screen reloads through its usual endpoint.
 */
interface LiveModuleApi {
    fun ticket(topic: String): LiveTicket
}

/** A signed, short-lived right to follow one topic. */
data class LiveTicket(
    val ticket: String,
    val expiresAt: Instant,
)
