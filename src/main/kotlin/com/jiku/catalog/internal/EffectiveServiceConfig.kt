package com.jiku.catalog.internal

import com.jiku.shared.ReminderChannel

/**
 * Options effectives d'un service (JIKU-86) : la valeur renseignée dans
 * service_config si elle existe, sinon le défaut de configuration. Le moteur et
 * les parcours ultérieurs ne lisent que ce modèle — jamais les colonnes nulles ni
 * les littéraux.
 */
data class EffectiveServiceConfig(
    val confirmationMode: ConfirmationMode,
    val stepMinutes: Int,
    val durationMinutes: Int,
    val bufferMinutes: Int,
    val minHorizonMinutes: Int,
    val maxHorizonDays: Int,
    val holdMinutes: Long,
    val cancelDeadlineHours: Int,
    val noShowToleranceMinutes: Int,
    val walkInsAllowed: Boolean,
    val reminderChannel: ReminderChannel,
    val reminderOffsetsMinutes: List<Int>,
    /** Clients served together per resource and per slot, within the plan's cap (JIKU-174). */
    val clientsPerSlot: Int = 1,
    /** The most clients per slot the tenant's plan allows. */
    val maxClientsPerSlot: Int = 1,
) {
    /** Durée totale réservée sur la grille : service + tampon de respiration. */
    val occupancyMinutes: Long
        get() = durationMinutes.toLong() + bufferMinutes
}

/** Options à écrire sur un service (JIKU-86) : seules les valeurs non nulles changent. */
data class ServiceConfigUpdate(
    val confirmationMode: ConfirmationMode? = null,
    val stepMinutes: Int? = null,
    val durationMinutes: Int? = null,
    val bufferMinutes: Int? = null,
    val minHorizonMinutes: Int? = null,
    val maxHorizonDays: Int? = null,
    val holdMinutes: Long? = null,
    val cancelDeadlineHours: Int? = null,
    val noShowToleranceMinutes: Int? = null,
    val walkInsAllowed: Boolean? = null,
    val reminderChannel: ReminderChannel? = null,
    val reminderOffsetsMinutes: List<Int>? = null,
    val clientsPerSlot: Int? = null,
)
