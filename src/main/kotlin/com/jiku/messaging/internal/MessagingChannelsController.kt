package com.jiku.messaging.internal

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Which channels the platform can send on, so screens only offer what will really go out. */
data class MessagingChannelsResponse(
    /** False when SMS is turned off for the platform (JIKU-261): no SMS option or "or by SMS" copy is shown. */
    val sms: Boolean,
)

@RestController
@RequestMapping("/messaging/channels")
class MessagingChannelsController(
    private val smsSender: SmsSender,
) {
    @GetMapping
    fun channels(): MessagingChannelsResponse = MessagingChannelsResponse(sms = smsSender.enabled)
}
