package com.jiku.allocation

/**
 * Ordonnancement d'une numérotation séquentielle sans trou, commun à la facture
 * et au rang du jour. Le port [CounterStore] vit chez le module qui possède la
 * table ; le module allocation ne connaît ni money ni ticket.
 *
 * Le contrat de concurrence appartient à l'implémentation du port :
 * - [ensureRow] crée la ligne du compteur en propre transaction (une course
 *   perdue est rattrapée par l'appelant du port) ;
 * - [nextValue] lit sous verrou pessimiste et incrémente, verrou tenu jusqu'à
 *   l'engagement de la transaction appelante — un repli rend le numéro.
 */
class AtomicCounter(
    private val store: CounterStore,
) {
    fun next(): Long {
        store.ensureRow()
        return store.nextValue()
    }

    interface CounterStore {
        fun ensureRow()

        fun nextValue(): Long
    }
}
