package com.jiku.ticket.internal

/**
 * Ce que représente un ticket (JIKU-83). [INVITATION] est émis par le RSVP d'un
 * événement ; [APPOINTMENT] (rendez-vous sur créneau) et [WALK_IN] (sans-rendez-vous,
 * pas de créneau) par le produit service.
 *
 * Les valeurs de la colonne sont ces noms en anglais (INVITATION / APPOINTMENT /
 * WALK_IN) ; la documentation produit les désigne comme INVITATION / RENDEZ_VOUS /
 * SANS_RENDEZ_VOUS.
 */
enum class TicketKind {
    INVITATION,
    APPOINTMENT,
    WALK_IN,
}
