package com.jiku.shared

/**
 * Whether the current organization's services are paused because its
 * subscription ended (JIKU-254). A pause stops new bookings and walk-in
 * tickets only: clients already booked keep their appointment, and the
 * organization's events, tickets and account stay untouched. Exposed in
 * `shared` so the catalog module asks without depending on the money module,
 * which implements it. Reads the tenant bound to the current context.
 */
interface ServicesAccessGate {
    fun servicesPaused(): Boolean
}
