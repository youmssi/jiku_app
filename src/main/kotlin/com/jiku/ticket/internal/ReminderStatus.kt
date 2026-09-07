package com.jiku.ticket.internal

/** Déroulement d'un rappel (JIKU-89). */
enum class ReminderStatus {
    /** Réservé par le balayage ; l'envoi suit dans la même transaction. */
    PENDING,

    /** Expédié avec succès. */
    SENT,

    /** Retenu par une garde-fou de coût (fenêtre 24 h…) : à rejouer au balayage. */
    QUEUED,

    /** Échec définitif après tentative(s) ; journalisé, jamais remonté au client. */
    FAILED,
}
