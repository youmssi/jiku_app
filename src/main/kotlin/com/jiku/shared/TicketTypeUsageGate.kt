package com.jiku.shared

import java.util.UUID

/**
 * Compte les invités rattachés à une catégorie d'accès (JIKU-93), exposé dans
 * `shared` pour la même raison que [UsageAllowanceGate] : `catalog` a besoin de
 * ce chiffre avant de supprimer une catégorie, mais `invitation` dépend déjà de
 * `catalog` pour lire ses événements, et une dépendance en retour formerait un
 * cycle.
 *
 * `invitation` fournit l'implémentation ; `catalog` consomme cette interface.
 */
interface TicketTypeUsageGate {
    /**
     * Nombre d'invités rattachés à cette catégorie. Zéro autorise la suppression :
     * au-delà, supprimer laisserait des billets déjà émis sans catégorie, et le
     * portier ne saurait plus où admettre leur porteur.
     */
    fun countGuestsWithTicketType(ticketTypeId: UUID): Long
}
