package com.jiku.shared

import java.time.Instant

/**
 * The moment each organization first reached a step (JIKU-202), keyed by tenant
 * id: first event, first guest, first send, first payment. Modules compute it
 * with a cross-tenant native query, deliberately outside the tenant filter, and
 * only the platform back-office reads it.
 */
typealias TenantMilestones = Map<String, Instant>

/** Turns `(tenant_id, timestamp)` rows of a native query into [TenantMilestones]. */
fun tenantMilestones(rows: List<Array<Any?>>): TenantMilestones =
    rows
        .mapNotNull { row ->
            val tenantId = row[0]?.toString() ?: return@mapNotNull null
            val at = row[1]?.let(::nativeInstant) ?: return@mapNotNull null
            tenantId to at
        }.toMap()

/** A native-query timestamp column, whichever type the JDBC driver surfaced it as. */
fun nativeInstant(value: Any): Instant =
    when (value) {
        is Instant -> value
        is java.sql.Timestamp -> value.toInstant()
        is java.time.OffsetDateTime -> value.toInstant()
        else -> Instant.parse(value.toString())
    }
