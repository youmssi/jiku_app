package com.jiku.notification.internal

import com.jiku.shared.DemoSeedContributor
import org.springframework.stereotype.Component

/**
 * Clears the demo tenant's notification audit log so a re-seed does not accumulate
 * records referencing deleted invitations. Runs last on wipe (highest order); it
 * seeds nothing, since the demo's invitations are recreated as already SENT.
 */
@Component
class NotificationDemoSeedContributor(
    private val logs: NotificationLogRepository,
) : DemoSeedContributor {
    override val order = 50

    override fun wipe() {
        logs.deleteAll()
    }
}
