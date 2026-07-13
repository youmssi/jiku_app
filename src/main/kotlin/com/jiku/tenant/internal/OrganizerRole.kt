package com.jiku.tenant.internal

import com.jiku.shared.security.TokenRoles

/**
 * A member's role within one organization (JIKU-48). OWNER manages everything
 * including members and billing; ADMIN manages operations and organization
 * settings; MEMBER operates events without touching organization management.
 * [tokenRole] is the JWT role claim the role is carried as.
 */
enum class OrganizerRole(
    val tokenRole: String,
) {
    OWNER(TokenRoles.ORGANIZER_OWNER),
    ADMIN(TokenRoles.ORGANIZER_ADMIN),
    MEMBER(TokenRoles.ORGANIZER_MEMBER),
}
