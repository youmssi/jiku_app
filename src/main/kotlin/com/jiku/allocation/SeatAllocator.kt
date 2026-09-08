package com.jiku.allocation

/**
 * Décide si une place peut être prise dans une capacité finie — le prédicat que
 * chaque module propriétaire applique ensuite à son compteur (Event, TicketType,
 * et demain liste d'attente). Module `allocation` : aucune dépendance métier.
 */
object SeatAllocator {
    /** Une jauge sans limite accueille toujours ; sinon il faut rester sous la limite. */
    fun isOpen(
        current: Long,
        limit: Long?,
    ): Boolean = limit == null || current < limit

    /**
     * Tente de prendre une place et renvoie le nouvel état à écrire. Le décideur
     * est pur ; c'est l'écriture conditionnelle de l'appelant qui garantit qu'à
     * l'engagement, deux preneurs concurrents n'ont pas pu lire le même état.
     */
    fun take(
        current: Long,
        limit: Long?,
    ): Seat =
        if (isOpen(current, limit)) {
            Seat(taken = true, nextCount = current + 1)
        } else {
            Seat(taken = false, nextCount = current)
        }

    data class Seat(
        val taken: Boolean,
        val nextCount: Long,
    )
}
