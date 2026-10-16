package com.jiku.catalog.internal

import com.jiku.shared.ReminderChannel
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalTime

/**
 * Défauts des options de service qui ne relèvent pas de la grille (JIKU-86).
 * Les défauts de grille (pas, durée, tampon, horizon min, blocage) restent dans
 * `catalog.slot.*` (SlotGridProperties). Toute valeur peut être surchargée par
 * service dans service_config ; le dossier produit §5 fixe ces défauts.
 */
@ConfigurationProperties(prefix = "catalog.service")
data class ServiceDefaultsProperties(
    val confirmationMode: ConfirmationMode = ConfirmationMode.ON_REQUEST,
    val maxHorizonDays: Int = 30,
    val cancelDeadlineHours: Int = 24,
    val noShowToleranceMinutes: Int = 10,
    val walkInsAllowed: Boolean = true,
    val reminderChannel: ReminderChannel = ReminderChannel.NONE,
    val reminderOffsetsMinutes: List<Int> = listOf(1440, 120),
    /** Opening hours given to the person a quick-set-up service creates (JIKU-252), Monday = 1. */
    val openingDays: List<Int> = listOf(1, 2, 3, 4, 5, 6),
    val opensAt: LocalTime = LocalTime.of(9, 0),
    val closesAt: LocalTime = LocalTime.of(18, 0),
)
