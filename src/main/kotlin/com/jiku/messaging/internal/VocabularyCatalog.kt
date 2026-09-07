package com.jiku.messaging.internal

/**
 * Termes produit surchargeables par tenant (JIKU-91). [label] décrit l'usage du
 * terme dans l'éditeur ; [defaultValue] est le terme du produit. L'absence de
 * surcharge = terme par défaut, donc chaque nouveau métier n'exige aucune
 * modification de code.
 */
object VocabularyCatalog {
    data class Term(
        val key: String,
        val label: String,
        val defaultValue: String,
    )

    val terms: List<Term> =
        listOf(
            Term("ticket", "Ticket / counter label", "ticket"),
            Term("appointment", "Booked appointment", "rendez-vous"),
            Term("walkIn", "Walk-in (no appointment)", "sans rendez-vous"),
            Term("person", "Person", "personne"),
            Term("professional", "Professional", "professionnel"),
            Term("action", "Action", "action"),
        )

    fun term(key: String): Term? = terms.firstOrNull { it.key == key }
}
