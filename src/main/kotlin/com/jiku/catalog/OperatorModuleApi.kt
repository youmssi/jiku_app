package com.jiku.catalog

import java.util.UUID

/** What an operator may do within their scope (JIKU-116). */
enum class OperatorAction {
    /** Scan and check in tickets at an event's door. */
    CHECK_IN,

    /** Run a service's day line: call, serve, add walk-ins, confirm requests. */
    QUEUE,

    /** Record that a client paid the organization. */
    COLLECT,
}

/**
 * Operator links as seen by the other modules (JIKU-116). An operator works
 * through a signed link rather than an account; every request made with it is
 * resolved here, so scope, revocation and suspension are checked in one place.
 */
interface OperatorModuleApi {
    /**
     * Runs [block] as the operator behind [token] on one of their events, with the
     * operator's tenant bound for its duration. [eventId] may be omitted when the
     * link names its event (links opened before a single console existed) or the
     * operator covers exactly one event. [action], when given, must be allowed.
     * Throws 404 for an unknown, expired, revoked or suspended link or an event out
     * of its scope, and 403 for an action it does not allow.
     */
    fun <T> onEvent(
        token: String,
        eventId: UUID?,
        action: OperatorAction?,
        block: (event: OperatorTarget) -> T,
    ): T

    /** Labels of every operator an event has had, for the per-entrance breakdown. */
    fun eventOperatorLabels(eventId: UUID): List<String>
}

/** The event or service an operator request acts on, and who acts. */
data class OperatorTarget(
    val id: UUID,
    val operatorLabel: String,
    val actions: Set<OperatorAction>,
)
