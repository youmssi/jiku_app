package com.jiku.catalog.internal

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
    val paymentMode: PaymentMode,
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
    val paymentMode: PaymentMode? = null,
)
