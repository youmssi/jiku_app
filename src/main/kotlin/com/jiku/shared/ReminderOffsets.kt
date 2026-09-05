package com.jiku.shared

/**
 * Format de la colonne service_config.reminder_offsets_minutes (JIKU-89) : les
 * décalages (minutes avant le créneau) séparés par des points-virgules, du plus
 * grand au plus petit — ex. « 1440;120 » = rappel J-1 puis H-2. Le format est
 * partagé entre le module catalog (lecture/écriture de la configuration) et le
 * module ticket (balayage des rappels dus sur la même colonne).
 */
object ReminderOffsets {
    const val SEPARATOR = ";"

    /** Décode la colonne ; null quand elle est vide ou absente. */
    fun parse(raw: String?): List<Int>? {
        val values =
            raw
                ?.split(SEPARATOR)
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it > 0 }
                ?: emptyList()
        return values.takeIf { it.isNotEmpty() }
    }

    /** Encode les décalages : uniques, triés du plus grand au plus petit. */
    fun encode(offsets: List<Int>): String =
        offsets
            .filter { it > 0 }
            .distinct()
            .sortedDescending()
            .joinToString(SEPARATOR)
}
