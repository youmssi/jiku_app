package com.jiku.messaging.internal

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset

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
     * Envoie un avis opérationnel simple (titre + paragraphe) dans [language].
     * [label] sert aux logs (« trial », « subscription »…).
     */
    fun sendNotice(
        label: String,
        to: String,
        toName: String,
        language: String,
        copy: NoticeCopy,
    ) {
        val rendered = templateRenderer.renderNotice(language, toName, copy.subject, copy.heading, copy.body)
        send(label, to, toName, rendered)
    }

    /**
     * Envoie un e-mail opérationnel déjà rendu (squelette commun : adresse vide,
     * échec silencieux). [label] sert aux logs.
     */
    fun send(
        label: String,
        to: String,
        toName: String,
        email: RenderedEmail,
    ) {
        if (to.isBlank()) {
            log.warn("Skipping {} email: no recipient address", label)
            return
        }
        try {
            emailSender.send(
                emailProperties.from,
                EmailMessage(to = to, toName = toName, subject = email.subject, htmlBody = email.html),
            )
        } catch (ex: Exception) {
            log.error("Failed to send {} email to {}", label, to, ex)
        }
    }
}

/** Objet, titre et paragraphe d'un avis opérationnel, déjà dans la langue du destinataire. */
data class NoticeCopy(
    val subject: String,
    val heading: String,
    val body: String,
)

/**
 * La copie d'un avis `<prefix>.<kind>.{subject,heading,body}` du catalogue, ou
 * null quand ce type d'avis n'a pas de texte.
 */
internal fun MessageCatalog.noticeCopy(
    language: String,
    prefix: String,
    kind: String,
    values: Map<String, String>,
): NoticeCopy? {
    val subject = textOrNull(language, "$prefix.$kind.subject", values) ?: return null
    return NoticeCopy(
        subject = subject,
        heading = text(language, "$prefix.$kind.heading", values),
        body = text(language, "$prefix.$kind.body", values),
    )
}

/** Date d'un avis opérationnel, en UTC, écrite dans [language]. */
internal fun MessageCatalog.formatOperationalInstant(
    language: String,
    instant: Instant,
): String = formatDate(language, "date.operational", instant, ZoneOffset.UTC)
