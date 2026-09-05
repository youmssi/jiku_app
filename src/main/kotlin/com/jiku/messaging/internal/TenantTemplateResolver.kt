package com.jiku.messaging.internal

import com.jiku.shared.OpsAlert
import com.jiku.shared.TenantContext
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/**
 * Résolution d'un gabarit client (JIKU-91) à l'envoi : corps du tenant s'il
 * existe et est actif, sinon corps par défaut du build. Un gabarit du tenant qui
 * laisse une variable {{…}} non substituée est jugé invalide : repli sur le défaut
 * et alerte d'exploitation — un gabarit invalide n'empêche jamais un envoi.
 */
@Component
class TenantTemplateResolver(
    private val templates: TenantTemplateRepository,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(TenantTemplateResolver::class.java)

    /** Corps surchargé et actif du tenant, ou null (défaut du produit). */
    fun overrideBody(
        name: String,
        channel: String,
    ): String? {
        if (TenantContext.get() == null) {
            return null
        }
        return templates
            .findByNameAndChannel(name, channel)
            ?.takeIf { it.active }
            ?.body
    }

    /**
     * Rend [base] (défaut du build) ou, quand elle est valide, la surcharge du
     * tenant. Les valeurs de [values] sont déjà échappées pour le canal (HTML pour
     * l'e-mail, brut pour WhatsApp) par l'appelant.
     */
    fun render(
        name: String,
        channel: String,
        base: String,
        values: Map<String, String>,
    ): String {
        val override = overrideBody(name, channel)
        if (override == null) {
            return substitute(base, values)
        }
        val rendered = substitute(override, values)
        if (rendered.contains(UNSUBSTITUTED)) {
            log.warn("Tenant template {}/{} left a {{…}} unsubstituted — falling back to the default", name, channel)
            events.publishEvent(
                OpsAlert(
                    subject = "Invalid tenant template $name/$channel",
                    message =
                        "A tenant override for $name/$channel contained an unknown variable and was " +
                            "replaced by the default at send time. Review the override in the tenant's settings.",
                ),
            )
            return substitute(base, values)
        }
        return rendered
    }

    fun substitute(
        body: String,
        values: Map<String, String>,
    ): String = values.entries.fold(body) { acc, (key, value) -> acc.replace("{{$key}}", value) }

    private companion object {
        const val UNSUBSTITUTED = "{{"
    }
}

/** Charge un gabarit par défaut depuis les ressources du build. */
object ClientTemplateDefaults {
    private val cache = mutableMapOf<String, String>()

    fun load(
        name: String,
        channel: String,
    ): String? {
        val definition = ClientTemplates.definition(name) ?: return null
        val file = definition.defaultFile(channel) ?: return null
        val folder = if (channel == ClientTemplates.CHANNEL_EMAIL) "email-templates" else "whatsapp-templates"
        val key = "$channel/$file"
        return cache.getOrPut(key) {
            ClassPathResource("$folder/$file").inputStream.bufferedReader().use { it.readText() }
        }
    }
}
