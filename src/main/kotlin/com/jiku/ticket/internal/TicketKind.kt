package com.jiku.ticket.internal

/**
 * Ce que représente un ticket (JIKU-83). Les tickets d'invitation existants
 * prennent [INVITATION] ; l'offre rendez-vous ajoutera [APPOINTMENT] (rendez-vous
 * sur créneau) et [WALK_IN] (sans-rendez-vous, pas de créneau).
 *
 * Les valeurs de la colonne sont ces noms en anglais (INVITATION / APPOINTMENT /
 * WALK_IN) ; la documentation produit les désigne comme INVITATION / RENDEZ_VOUS /
 * SANS_RENDEZ_VOUS. Aucun ticket non-invitation n'est émis aujourd'hui, ce choix de
 * nom ne lie donc aucune donnée existante.
 */
enum class TicketKind {
    INVITATION,
    APPOINTMENT,
    WALK_IN,
}
