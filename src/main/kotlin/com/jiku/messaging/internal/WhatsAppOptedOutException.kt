package com.jiku.messaging.internal

/** The recipient wrote STOP (JIKU-143); never retried, only lifted by the recipient writing START. */
class WhatsAppOptedOutException(
    message: String,
) : RuntimeException(message)
