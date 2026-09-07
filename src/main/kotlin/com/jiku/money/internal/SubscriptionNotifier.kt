package com.jiku.money.internal

import com.jiku.shared.SubscriptionNotice
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * Publie un [SubscriptionNotice] (JIKU-90) avec le nom et l'e-mail de
 * l'organisateur, résolus depuis le module tenant — le même découpage que
 * [com.jiku.shared.TrialNotice] : money émet, notification envoie.
 */
@Component
class SubscriptionNotifier(
    private val events: ApplicationEventPublisher,
    private val tenantModuleApi: TenantModuleApi,
) {
    fun send(
        kind: String,
        tenantId: String,
        plan: String,
        months: Int? = null,
        amountMinor: Long? = null,
        currency: String? = null,
        reference: String? = null,
        expiresAt: Instant? = null,
        suspensionAt: Instant? = null,
        note: String? = null,
    ) {
        val tenant = runCatching { tenantModuleApi.findTenant(UUID.fromString(tenantId)) }.getOrNull()
        events.publishEvent(
            SubscriptionNotice(
                kind = kind,
                tenantId = tenantId,
                plan = plan,
                months = months,
                amountMinor = amountMinor,
                currency = currency,
                reference = reference,
                expiresAt = expiresAt,
                suspensionAt = suspensionAt,
                organizerName = tenant?.displayName ?: tenant?.name ?: "Organizer",
                organizerEmail = tenant?.contactEmail ?: "",
                note = note,
            ),
        )
    }
}
