package com.jiku.backoffice.internal

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Vérification de bout en bout de la chaîne de remontée d'erreurs (JIKU-97).
 *
 * Un DSN renseigné ne prouve rien : la clé peut être fausse, le projet supprimé,
 * le réseau sortant bloqué. La seule preuve qu'une exception de production
 * atteint le tableau de bord est d'en provoquer une et de l'y voir apparaître.
 *
 * Cet endpoint existe parce qu'il n'y a pas d'autre moyen honnête de le faire :
 * les 404, 409 et 422 sont traités par les handlers de Spring et ne remontent
 * pas — à juste titre. Il faut une exception réellement non gérée, et chercher
 * un plantage exploitable en production serait à la fois hasardeux et
 * révélateur d'un défaut qu'on préférerait ne pas avoir.
 *
 * Il est sous `/admin`, donc réservé au rôle `PLATFORM_ADMIN` par la même règle
 * que le reste du back-office. Il n'écrit rien, ne lit rien, ne touche à aucune
 * donnée : il lève une exception et rien d'autre. Le pire qu'il puisse produire
 * est une ligne de bruit dans le tableau de bord d'erreurs.
 *
 * À rejouer après chaque changement de DSN ou d'environnement.
 */
@RestController
@RequestMapping("/admin/diagnostics")
@PreAuthorize("hasRole('PLATFORM_ADMIN')")
class AdminDiagnosticsController {
    /**
     * Lève une exception non gérée, que [com.jiku.shared.observability.GlobalExceptionHandler]
     * transmet au traqueur avant de répondre 500.
     *
     * L'appelant reçoit un 500 portant son `requestId` : c'est ce même
     * identifiant qu'il doit retrouver dans l'événement du tableau de bord, ce
     * qui prouve que les deux bouts parlent bien de la même requête.
     */
    @PostMapping("/error")
    fun triggerError(): Nothing =
        throw ErrorPipelineProbeException(
            "Vérification volontaire de la chaîne de remontée d'erreurs (JIKU-97). " +
                "Cette exception est délibérée : elle ne signale aucun incident.",
        )
}

/**
 * Type dédié plutôt qu'une exception générique : il rend l'événement
 * identifiable au premier coup d'œil dans le tableau de bord, et il peut être
 * filtré d'une alerte sans masquer de vraies pannes.
 */
class ErrorPipelineProbeException(
    message: String,
) : RuntimeException(message)
