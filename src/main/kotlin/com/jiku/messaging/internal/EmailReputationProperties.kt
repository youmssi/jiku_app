package com.jiku.messaging.internal

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Sender-reputation thresholds and the webhook shared secret. Rates are evaluated
 * over a rolling [windowHours] window. The alert thresholds sit below AWS SES's own
 * review limits (5% bounce, 0.1% complaint), because SES judges the whole shared
 * account and one tenant's list can otherwise stop sending for everyone (ADR 107).
 * All overridable per environment.
 */
@ConfigurationProperties(prefix = "notification.reputation")
data class EmailReputationProperties(
    /** Shared secret required on the provider-agnostic webhook (X-Webhook-Secret header). */
    val webhookSecret: String = "local-development-webhook-secret",
    /** Svix signing secret (whsec_…) for Resend's webhook; endpoint is inert until set. */
    val resendWebhookSecret: String? = null,
    /** Signing secret of the useSend webhook (ADR 107); endpoint is inert until set. */
    val usesendWebhookSecret: String? = null,
    val windowHours: Long = 24,
    val bounceRateThreshold: Double = 0.02,
    val complaintRateThreshold: Double = 0.0008,
    /** Minimum sends in the window before a rate is considered meaningful. */
    val minSampleSize: Long = 20,
    /** Hard bounces for one address before it is treated as undeliverable. */
    val undeliverableHardBounces: Int = 2,
    /** Optional address alerted when a platform threshold is breached. */
    val opsAlertEmail: String? = null,
)
