package com.jiku.shared.security

/**
 * Role claim values carried in JWTs and their expansion into Spring authorities.
 * Organizer roles nest: an owner can do everything a manager can, a manager
 * everything a member can, and every signed-in account holds the base USER
 * authority (enough to read its own profile and create an organization).
 *
 * `ORGANIZER_ADMIN` also covers tokens issued before memberships existed (JIKU-48):
 * those accounts were sole owners and re-gain the owner authorities on their next
 * refresh, when the role is re-derived from the backfilled OWNER membership.
 */
object TokenRoles {
    const val USER = "USER"
    const val ORGANIZER_MEMBER = "ORGANIZER_MEMBER"
    const val ORGANIZER_ADMIN = "ORGANIZER_ADMIN"
    const val ORGANIZER_OWNER = "ORGANIZER_OWNER"

    fun expand(role: String): List<String> =
        when (role) {
            ORGANIZER_OWNER -> listOf("ORGANIZER_OWNER", "ORGANIZER_MANAGER", "ORGANIZER", USER)
            ORGANIZER_ADMIN -> listOf("ORGANIZER_MANAGER", "ORGANIZER", USER)
            ORGANIZER_MEMBER -> listOf("ORGANIZER", USER)
            USER -> listOf(USER)
            else -> listOf(role)
        }
}
