package com.jiku.catalog

/**
 * The client an event is run for (ADR 105): a planner or an agency sends each
 * event under its client's name, logo and colour instead of its own. Every part
 * left empty falls back to the organization's branding.
 */
data class EventBrand(
    val name: String? = null,
    val logoUrl: String? = null,
    val primaryColor: String? = null,
    /** How the guest-facing surfaces look (JIKU-194). */
    val cardStyle: CardStyle = CardStyle.MODERN,
    /** The public link of the event's banner photo; null without one. */
    val bannerUrl: String? = null,
)
