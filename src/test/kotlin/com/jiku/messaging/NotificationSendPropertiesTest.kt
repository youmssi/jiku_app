package com.jiku.messaging

import com.jiku.messaging.internal.NotificationSendProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class NotificationSendPropertiesTest {
    @Test
    fun `each new attempt waits its configured delay, the last one repeating`() {
        val properties = NotificationSendProperties(maxAttempts = 4, retryBackoffMs = listOf(2_000, 8_000))

        assertEquals(listOf(2_000L, 8_000L, 8_000L), (2..4).map { properties.backoffBefore(it) })
        assertEquals(0, NotificationSendProperties(retryBackoffMs = emptyList()).backoffBefore(2))
    }
}
