package com.jiku.messaging.internal

import com.jiku.shared.SubscriptionNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * E-mails d'abonnement à l'organisateur (JIKU-90) : échéance proche (J-7),
 * entrée en grâce, suspension, réactivation — dans la langue de l'organisation.
 * Contenu uniquement — la mécanique d'envoi vit dans [OperationalMailer]. Jamais
 * une carte, jamais un identifiant de paiement.
 */
@Component
class SubscriptionNoticeListener(
    private val mailer: OperationalMailer,
    private val catalog: MessageCatalog,
) {
    private val log = LoggerFactory.getLogger(SubscriptionNoticeListener::class.java)

    @EventListener
    fun onSubscriptionNotice(notice: SubscriptionNotice) {
        val language = catalog.language(notice.tenantId)
        val values =
            mapOf(
                "plan" to notice.plan,
                "until" to notice.expiresAt?.let { catalog.formatOperationalInstant(language, it) }.orEmpty(),
                "suspended" to notice.suspensionAt?.let { catalog.formatOperationalInstant(language, it) }.orEmpty(),
            )
        val copy = catalog.noticeCopy(language, "subscription", notice.kind, values)
        if (copy == null) {
            log.warn("Ignoring subscription notice of unknown kind: {}", notice.kind)
            return
        }
        mailer.sendNotice("subscription", notice.organizerEmail, notice.organizerName, language, copy)
    }
}
