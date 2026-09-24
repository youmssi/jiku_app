package com.jiku.tenant.internal

import com.jiku.shared.MessageLanguage
import com.jiku.shared.TenantLanguage
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** An organization writes to its clients in the language of its country. */
@Component
class TenantLanguageAdapter(
    private val tenants: TenantRepository,
) : TenantLanguage {
    @Transactional(readOnly = true)
    override fun of(tenantId: String): String =
        runCatching { UUID.fromString(tenantId) }
            .getOrNull()
            ?.let { tenants.findById(it).orElse(null) }
            ?.let { MessageLanguage.forCountry(it.country) }
            ?: MessageLanguage.FRENCH
}
