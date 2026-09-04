package com.jiku.catalog.internal

import org.springframework.transaction.annotation.Transactional
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
) {
    /** Options effectives du service : renseignées ou défaut. */
    @Transactional(readOnly = true)
    fun effective(serviceId: UUID): EffectiveServiceConfig {
        val config = configs.findById(serviceId).orElse(null)
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
            paymentMode = config?.paymentMode ?: serviceDefaults.paymentMode,
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
        update.paymentMode?.let { config.paymentMode = it }
        configs.save(config)
        return effective(serviceId)
    }
}
