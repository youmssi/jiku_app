package com.jiku.catalog.internal

import com.jiku.catalog.OperatorAction
import com.jiku.catalog.OperatorModuleApi
import com.jiku.catalog.OperatorTarget
import com.jiku.shared.TenantAccessGate
import com.jiku.shared.TenantContext
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * Resolves every request made through an operator link (JIKU-116): the link must
 * verify, belong to an organization that is not suspended, name an operator that
 * is not revoked, and reach an event or service within that operator's scope.
 * The operator's tenant is bound for the duration of the work and always cleared
 * afterwards. An event or service out of scope answers exactly like an unknown
 * one, so a link never reveals what else the organization runs.
 */
@SpringService
class OperatorGate(
    private val tokens: OperatorTokenService,
    private val operators: OperatorRepository,
    private val tenantAccessGate: TenantAccessGate,
) : OperatorModuleApi {
    override fun <T> onEvent(
        token: String,
        eventId: UUID?,
        action: OperatorAction?,
        block: (event: OperatorTarget) -> T,
    ): T =
        asOperator(token) { operator, claims ->
            block(operator.target(eventId ?: claims.pinnedEventId, operator.eventIds, action, "event"))
        }

    fun <T> onService(
        token: String,
        serviceId: UUID?,
        action: OperatorAction?,
        block: (service: OperatorTarget) -> T,
    ): T =
        asOperator(token) { operator, claims ->
            block(operator.target(serviceId ?: claims.pinnedServiceId, operator.serviceIds, action, "service"))
        }

    /** Runs [block] as the active operator behind [token], with their tenant bound. */
    fun <T> asOperator(
        token: String,
        block: (Operator, OperatorClaims) -> T,
    ): T {
        val claims = tokens.parse(token) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, INVALID_LINK)
        if (tenantAccessGate.isSuspended(claims.tenantId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link is no longer available")
        }
        TenantContext.set(claims.tenantId)
        try {
            val operator =
                operators.findWithScopeById(claims.operatorId)
                    ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, INVALID_LINK)
            if (operator.revoked) {
                throw ResponseStatusException(HttpStatus.FORBIDDEN, "This link has been revoked")
            }
            return block(operator, claims)
        } finally {
            TenantContext.clear()
        }
    }

    override fun eventOperatorLabels(eventId: UUID): List<String> = operators.findByEvent(eventId).map { it.label }

    private fun Operator.target(
        requestedId: UUID?,
        scope: Set<UUID>,
        action: OperatorAction?,
        kind: String,
    ): OperatorTarget {
        val targetId =
            requestedId ?: scope.singleOrNull()
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Choose the $kind to work on from the operator console")
        if (targetId !in scope) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "This link does not cover this $kind")
        }
        val allowed = actions
        if (action != null && action !in allowed) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "This link does not allow ${action.describe()}")
        }
        return OperatorTarget(id = targetId, operatorLabel = label, actions = allowed)
    }

    private fun OperatorAction.describe(): String =
        when (this) {
            OperatorAction.CHECK_IN -> "checking guests in"
            OperatorAction.QUEUE -> "running the line"
            OperatorAction.COLLECT -> "recording payments"
        }

    private companion object {
        const val INVALID_LINK = "This link is invalid or has expired"
    }
}
