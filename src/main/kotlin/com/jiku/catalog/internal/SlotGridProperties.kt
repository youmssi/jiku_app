package com.jiku.catalog.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Défauts de la grille fixe du moteur de créneaux (JIKU-85). Lus depuis la
 * configuration (`catalog.slot.*`), jamais codés en dur ; la configuration par
 * service (JIKU-86) les remplacera.
 */
@ConfigurationProperties(prefix = "catalog.slot")
data class SlotGridProperties(
    val stepMinutes: Int = 30,
    val durationMinutes: Int = 30,
    val bufferMinutes: Int = 0,
    val minHorizonMinutes: Int = 120,
    val holdMinutes: Long = 1440,
)
