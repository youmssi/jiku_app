package com.jiku.notification.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Sender-reputation thresholds and the webhook shared secret. Rates are evaluated
 * over a rolling [windowHours] window; the alert thresholds follow common industry
 * guidance (stay well under ~5% bounce and ~0.5% complaint). All overridable per
 * environment.
 */
@ConfigurationProperties(prefix = "notification.reputation")
data class EmailReputationProperties(
    /** Shared secret required on the provider-agnostic webhook (X-Webhook-Secret header). */
    val webhookSecret: String = "local-development-webhook-secret",
    /** Svix signing secret (whsec_…) for Resend's webhook; endpoint is inert until set. */
    val resendWebhookSecret: String? = null,
    val windowHours: Long = 24,
    val bounceRateThreshold: Double = 0.05,
    val complaintRateThreshold: Double = 0.005,
    /** Minimum sends in the window before a rate is considered meaningful. */
    val minSampleSize: Long = 20,
    /** Hard bounces for one address before it is treated as undeliverable. */
    val undeliverableHardBounces: Int = 2,
    /** Optional address alerted when a platform threshold is breached. */
    val opsAlertEmail: String? = null,
)
