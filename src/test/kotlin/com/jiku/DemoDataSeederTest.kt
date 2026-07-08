package com.jiku

import com.jiku.invitation.internal.InvitationStatus
import com.jiku.invitation.internal.RsvpStatus
import com.jiku.shared.TenantContext
import com.jiku.ticketing.internal.TicketStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * JIKU-9D: one command seeds the demo tenant with events in every lifecycle state,
 * and re-running never needs manual cleanup first.
 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DemoDataSeederTest {
    @Autowired
    lateinit var seeder: DemoDataSeeder

    @Autowired
    lateinit var events: com.jiku.event.internal.EventRepository

    @Autowired
    lateinit var guests: com.jiku.invitation.internal.GuestRepository

    @Autowired
    lateinit var invitations: com.jiku.invitation.internal.InvitationRepository

    @Autowired
    lateinit var tickets: com.jiku.ticketing.internal.TicketRepository

    @Test
    fun `seeds a complete demo tenant and is idempotent`() {
        val first = seeder.seed()
        // Second run must not duplicate anything or require manual cleanup.
        val summary = seeder.seed()
        assertThat(summary.tenantId).isEqualTo(first.tenantId)

        TenantContext.set(summary.tenantId)
        try {
            val all = events.findAll()
            assertThat(all).hasSize(3)
            assertThat(all.map { it.status.name }).containsExactlyInAnyOrder("DRAFT", "PUBLISHED", "PUBLISHED")
            assertThat(all.map { it.name }).allSatisfy { assertThat(it).startsWith("[DEMO]") }

            // The published upcoming event has answered and unanswered RSVPs.
            val galaGuests = guests.findByEventId(summary.upcomingEventId)
            assertThat(galaGuests.map { it.rsvpStatus }).contains(
                RsvpStatus.CONFIRMED,
                RsvpStatus.DECLINED,
                RsvpStatus.PENDING,
            )

            // The past event carries a mixed check-in history.
            val pastTickets = tickets.findByEventId(summary.pastEventId)
            assertThat(pastTickets.count { it.status == TicketStatus.CHECKED_IN }).isEqualTo(5)
            assertThat(pastTickets.count { it.status == TicketStatus.ISSUED }).isEqualTo(3)
            assertThat(pastTickets.filter { it.status == TicketStatus.CHECKED_IN })
                .allSatisfy { assertThat(it.checkedInAt).isNotNull() }

            // Every guest was invited (a SENT invitation each) and is identifiable as demo data.
            val allGuests = guests.findAll()
            assertThat(invitations.findAll().map { it.status }).containsOnly(InvitationStatus.SENT)
            assertThat(allGuests.map { it.email }).allSatisfy {
                assertThat(it).endsWith("@demo.jiku.example")
            }
        } finally {
            TenantContext.clear()
        }
    }
}
