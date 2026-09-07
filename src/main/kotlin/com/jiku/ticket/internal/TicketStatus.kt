package com.jiku.ticket.internal

/**
 * Cycle d'un ticket. [ISSUED], [CHECKED_IN] et [CANCELLED] couvrent l'entrée
 * (invitation) ; les états de [WAITING] à [NO_SHOW] couvrent la ligne du jour
 * d'un service (JIKU-88) et ne sont jamais atteints par un billet d'invitation.
 *
 * Les noms sont en anglais comme ceux de la colonne ; la documentation produit
 * les désigne EN_ATTENTE / APPELÉ / EN_COURS / TERMINÉ / ABSENT.
 */
enum class TicketStatus {
    ISSUED,
    CHECKED_IN,
    CANCELLED,

    /** EN_ATTENTE — arrivé au comptoir, en attente d'appel. */
    WAITING,

    /** APPELÉ — le professionnel l'a appelé, il rejoint le poste. */
    CALLED,

    /** EN_COURS — pris en charge. */
    IN_SERVICE,

    /** TERMINÉ — servi. */
    DONE,

    /** ABSENT — appelé mais non présent (ou arrivé puis reparti). */
    NO_SHOW,
}
