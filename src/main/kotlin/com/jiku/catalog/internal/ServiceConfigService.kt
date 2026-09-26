package com.jiku.catalog.internal

import com.jiku.shared.GroupSessionGate
import com.jiku.shared.ReminderChannel
import com.jiku.shared.ReminderOffsets
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID
import org.springframework.stereotype.Service as SpringService

/**
 * Configuration par service (JIKU-86). Le moteur ne lit jamais service_config
 * directement : il passe par [effective], qui résout chaque option renseignée ou
 * retombe sur le défaut de configuration. Un service sans configuration est donc
 * pleinement utilisable (les défauts §5 s'appliquent).
 */
@SpringService
class ServiceConfigService(
    private val configs: ServiceConfigRepository,
    private val gridDefaults: SlotGridProperties,
    private val serviceDefaults: ServiceDefaultsProperties,
    private val groupSessions: GroupSessionGate,
) {
    /** Options effectives du service : renseignées ou défaut. */
    @Transactional(readOnly = true)
    fun effective(serviceId: UUID): EffectiveServiceConfig {
        val config = configs.findById(serviceId).orElse(null)
        val maxClientsPerSlot = groupSessions.maxClientsPerSlot()
        return EffectiveServiceConfig(
            confirmationMode = config?.confirmationMode ?: serviceDefaults.confirmationMode,
            stepMinutes = config?.stepMinutes ?: gridDefaults.stepMinutes,
            durationMinutes = config?.durationMinutes ?: gridDefaults.durationMinutes,
            bufferMinutes = config?.bufferMinutes ?: gridDefaults.bufferMinutes,
            minHorizonMinutes = config?.minHorizonMinutes ?: gridDefaults.minHorizonMinutes,
            maxHorizonDays = config?.maxHorizonDays ?: serviceDefaults.maxHorizonDays,
            holdMinutes = config?.holdMinutes ?: gridDefaults.holdMinutes,
            cancelDeadlineHours = config?.cancelDeadlineHours ?: serviceDefaults.cancelDeadlineHours,
            noShowToleranceMinutes = config?.noShowToleranceMinutes ?: serviceDefaults.noShowToleranceMinutes,
            walkInsAllowed = config?.walkInsAllowed ?: serviceDefaults.walkInsAllowed,
            reminderChannel = config?.reminderChannel ?: serviceDefaults.reminderChannel,
            reminderOffsetsMinutes =
                ReminderOffsets.parse(config?.reminderOffsetsMinutes) ?: serviceDefaults.reminderOffsetsMinutes,
            // A plan downgrade never lets a session keep more clients than the new plan allows.
            clientsPerSlot = (config?.clientsPerSlot ?: 1).coerceAtMost(maxClientsPerSlot),
            maxClientsPerSlot = maxClientsPerSlot,
        )
    }

    /** Persiste les options fournies (mise à jour partielle). */
    @Transactional
    fun update(
        serviceId: UUID,
        update: ServiceConfigUpdate,
    ): EffectiveServiceConfig {
        val config = configs.findById(serviceId).orElse(ServiceConfig(serviceId))
        update.confirmationMode?.let { config.confirmationMode = it }
        update.stepMinutes?.let { config.stepMinutes = it }
        update.durationMinutes?.let { config.durationMinutes = it }
        update.bufferMinutes?.let { config.bufferMinutes = it }
        update.minHorizonMinutes?.let { config.minHorizonMinutes = it }
        update.maxHorizonDays?.let { config.maxHorizonDays = it }
        update.holdMinutes?.let { config.holdMinutes = it }
        update.cancelDeadlineHours?.let { config.cancelDeadlineHours = it }
        update.noShowToleranceMinutes?.let { config.noShowToleranceMinutes = it }
        update.walkInsAllowed?.let { config.walkInsAllowed = it }
        update.reminderChannel?.let { channel ->
            config.reminderChannel = channel
            // Activer les rappels WhatsApp sans préciser de décalages applique les
            // défauts du produit (J-1/H-2) dès la persistance : le balayage des
            // rappels ne relit alors jamais une valeur nulle pour un canal activé
            // (JIKU-B2).
            if (channel != ReminderChannel.NONE && config.reminderOffsetsMinutes == null) {
                config.reminderOffsetsMinutes = ReminderOffsets.encode(serviceDefaults.reminderOffsetsMinutes)
            }
        }
        update.reminderOffsetsMinutes?.let { config.reminderOffsetsMinutes = ReminderOffsets.encode(it) }
        update.clientsPerSlot?.let { clients ->
            if (clients < 1) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A slot serves at least one client")
            }
            val max = groupSessions.maxClientsPerSlot()
            if (clients > max) {
                throw ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Your plan serves at most $max client(s) per slot; a larger group needs a higher plan",
                )
            }
            config.clientsPerSlot = clients
        }
        configs.save(config)
        return effective(serviceId)
    }
}
