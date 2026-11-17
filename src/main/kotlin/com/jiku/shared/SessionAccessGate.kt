package com.jiku.shared

/**
 * Whether a signed-in session is still open (JIKU-317). Consulted on every
 * request whose token names a session, so "sign out everywhere" takes effect on
 * the next request rather than when the access token expires.
 */
interface SessionAccessGate {
    fun isOpen(sessionId: String): Boolean
}
