package com.jiku.checkin.internal

import com.jiku.shared.DemoSeedContributor
import org.springframework.stereotype.Component

/**
 * Clears the demo tenant's validator links so a re-seed does not leave links
 * pointing at deleted events. Runs late (high order) so it wipes before the events
 * it references are recreated. No links are seeded — the demo's check-in history
 * lives on the tickets themselves.
 */
@Component
class CheckinDemoSeedContributor(
    private val validators: ValidatorRepository,
) : DemoSeedContributor {
    override val order = 40

    override fun wipe() {
        validators.deleteAll()
    }
}
