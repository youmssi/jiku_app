package com.jiku.live.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Live updates (JIKU-214). */
@ConfigurationProperties(prefix = "jiku.live")
data class LiveProperties(
    /** How long a ticket may be used to open a stream. */
    val ticketTtl: Duration = Duration.ofHours(12),
    /** How long a stream stays open; the browser then reconnects. */
    val streamTimeout: Duration = Duration.ofMinutes(30),
    /** Changes within this delay reach a screen as one signal. */
    val coalesce: Duration = Duration.ofMillis(500),
    /** A comment line sent this often keeps proxies (Cloudflare: 100 s) from closing an idle stream. */
    val heartbeat: Duration = Duration.ofSeconds(25),
    /** Streams open at once on this instance; beyond, new ones are refused and screens keep polling. */
    val maxStreams: Int = 2_000,
)
