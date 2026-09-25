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
)
