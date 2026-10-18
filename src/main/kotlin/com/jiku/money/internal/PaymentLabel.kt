package com.jiku.money.internal

/** What [payment] bought, in words, for receipts, invoices and the payment history; [eventName] names its event. */
fun paymentLabel(
    payment: Payment,
    eventName: String?,
): String =
    when (payment.kind) {
        Payment.KIND_SUBSCRIPTION -> "${payment.tier} subscription (${payment.subscriptionMonths} months)"
        Payment.KIND_PACK -> "Pro Organisateur (${payment.subscriptionMonths} months)"
        Payment.KIND_PACK_EXTRA -> "Pro Organisateur: ${payment.guests} extra guests"
        Payment.KIND_WHATSAPP_NUMBER -> "Own WhatsApp number (${payment.subscriptionMonths} months)"
        Payment.KIND_COMMISSION -> listOfNotNull("Ticket sales commission", eventName).joinToString(": ")
        else -> listOfNotNull(eventName ?: "Event", payment.guests?.let { "$it guests" }).joinToString(": ")
    }
