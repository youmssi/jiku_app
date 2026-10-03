package com.jiku.messaging.internal

import com.jiku.shared.OpsAlert
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Daily ceilings on what the platform pays for messages (ADR 107). Zero turns a
 * ceiling off. WhatsApp is the cost of the shared platform number only: an
 * organization's own number is billed to it by Meta.
 */
@ConfigurationProperties(prefix = "notification.spend-alert")
data class MessagingSpendAlertProperties(
    val whatsappDailyUsd: Double = 0.0,
    val smsDailyCount: Long = 0,
)

/**
 * Each morning, compares yesterday's (UTC) platform WhatsApp spend and SMS
 * volume with their ceilings and raises an [OpsAlert] when one is exceeded, so a
 * runaway campaign or a loop is noticed within a day rather than on the invoice.
 */
@Component
@EnableConfigurationProperties(MessagingSpendAlertProperties::class)
class MessagingSpendAlertJob(
    private val costs: WhatsAppMessageCostRepository,
    private val logs: NotificationLogRepository,
    private val properties: MessagingSpendAlertProperties,
    private val events: ApplicationEventPublisher,
) {
    @Scheduled(cron = "\${notification.spend-alert.cron:0 0 7 * * *}", zone = "UTC")
    @SchedulerLock(name = "MessagingSpendAlertJob.check")
    fun check() {
        check(LocalDate.now(ZoneOffset.UTC).minusDays(1))
    }

    fun check(day: LocalDate) {
        val from = day.atStartOfDay().toInstant(ZoneOffset.UTC)
        val to = day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        val breaches =
            buildList {
                if (properties.whatsappDailyUsd > 0) {
                    val usd = costs.sumPlatformUsdMinorBetween(from, to) / 100.0
                    if (usd > properties.whatsappDailyUsd) {
                        add("WhatsApp (platform number): %.2f USD, ceiling %.2f USD".format(usd, properties.whatsappDailyUsd))
                    }
                }
                if (properties.smsDailyCount > 0) {
                    val sms = logs.countSentByChannelBetween(SMS_CHANNEL, from, to)
                    if (sms > properties.smsDailyCount) {
                        add("SMS: $sms sent, ceiling ${properties.smsDailyCount}")
                    }
                }
            }
        if (breaches.isNotEmpty()) {
            events.publishEvent(
                OpsAlert(
                    subject = "Jikū messaging spend above ceiling on $day",
                    message = breaches.joinToString("; "),
                ),
            )
        }
    }

    private companion object {
        const val SMS_CHANNEL = "SMS"
    }
}
