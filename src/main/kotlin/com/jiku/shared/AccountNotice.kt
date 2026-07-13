package com.jiku.shared

/**
 * An account-lifecycle email request (JIKU-49), published by the tenant module
 * and consumed by the notification module — the same publisher/consumer split as
 * [ManualPaymentNotice]. The action URL already carries the single-use token;
 * the notification module only renders and sends.
 */
data class AccountNotice(
    val kind: String,
    val email: String,
    val actionUrl: String,
) {
    companion object {
        const val KIND_PASSWORD_RESET = "PASSWORD_RESET"
        const val KIND_EMAIL_VERIFICATION = "EMAIL_VERIFICATION"
    }
}
