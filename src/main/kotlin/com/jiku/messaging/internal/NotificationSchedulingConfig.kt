package com.jiku.messaging.internal

import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** Enables the scheduled sender-reputation monitor ([ReputationMonitorJob]). */
@Configuration
@EnableScheduling
class NotificationSchedulingConfig
