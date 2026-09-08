package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Courrier opérationnel émis par le sender plateforme (essais, abonnements,
 * paiements manuels…) : un seul endroit garde l'adresse vide, le rendu du gabarit
 * et la protection "un échec d'envoi ne doit jamais casser l'action qui l'a
 * déclenché". Les écouteurs ne décrivent plus que le contenu.
 */
@Component
class OperationalMailer(
    private val emailSender: EmailSender,
    private val emailProperties: NotificationEmailProperties,
    private val templateRenderer: EmailTemplateRenderer,
) {
    private val log = LoggerFactory.getLogger(OperationalMailer::class.java)

    /**
     * Envoie un e-mail opérationnel. [label] sert aux logs (« trial »,
     * « subscription »…) ; [heading]/[body] alimentent le gabarit d'avis existant.
     */
    fun sendOperational(
        label: String,
        to: String,
        toName: String,
        subject: String,
        heading: String,
        body: String,
    ) {
        if (to.isBlank()) {
            log.warn("Skipping {} email: no recipient address", label)
            return
        }
        try {
            emailSender.send(
                emailProperties.from,
                EmailMessage(
                    to = to,
                    toName = toName,
                    subject = subject,
                    htmlBody = templateRenderer.renderTrialNotice(toName, heading, body),
                ),
            )
        } catch (ex: Exception) {
            log.error("Failed to send {} email to {}", label, to, ex)
        }
    }
}

/** Format d'heure partagé des avis opérationnels, en UTC. */
internal fun formatOperationalInstant(instant: Instant): String = OPERATIONAL_UTC_FORMAT.format(instant.atZone(ZoneOffset.UTC))

private val OPERATIONAL_UTC_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm")
