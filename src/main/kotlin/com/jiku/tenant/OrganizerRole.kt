package com.jiku.tenant

/**
 * Organizer roles. Only [ORGANIZER_ADMIN] exists at MVP stage; the model is shaped
 * to extend to staff/validator roles later without rearchitecting.
 */
enum class OrganizerRole {
    ORGANIZER_ADMIN,
}
