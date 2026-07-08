package com.jiku.shared

import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Demo-data seeding contract (JIKU-9D). Each module contributes its own slice of
 * the demo tenant through an internal [DemoSeedContributor] — so no code outside a
 * module ever touches its internals — and the root-level orchestrator only
 * coordinates: prepare (tenant resolution), wipe (reverse order), seed (in order).
 *
 * What gets seeded is decided centrally in [DemoSeedPlan] so the pieces agree with
 * each other (an event's confirmed count matches the guests who confirm; a ticket
 * exists exactly for each confirmed guest) without the modules coordinating.
 */
interface DemoSeedContributor {
    /** Seed runs lowest-first; wipe runs highest-first (dependents clear first). */
    val order: Int

    /** Pre-tenant-context phase; only the tenant module resolves the tenant here. */
    fun prepare(context: DemoSeedContext) {}

    /** Deletes this module's demo-tenant data (tenant context is bound). */
    fun wipe() {}

    /** Creates this module's slice of the plan (tenant context is bound). */
    fun seed(context: DemoSeedContext) {}
}

/** Mutable state the contributors share while seeding one demo tenant. */
class DemoSeedContext {
    lateinit var tenantId: String
    val eventIds = mutableMapOf<String, UUID>()

    /** Guest ids keyed by the guest's demo email address. */
    val guestIds = mutableMapOf<String, UUID>()
}

/**
 * The single, static description of the demo tenant. Everything is recognizable
 * as demo data at a glance: `[DEMO]`-prefixed events, a dedicated organizer
 * address and guest addresses under `demo.jiku.example`.
 */
object DemoSeedPlan {
    const val TENANT_NAME = "Demo Events Co"
    const val ORGANIZER_EMAIL = "demo-organizer@jiku.example"
    const val TIMEZONE = "Africa/Abidjan"

    const val EVENT_LAUNCH = "launch"
    const val EVENT_GALA = "gala"
    const val EVENT_CONFERENCE = "conference"

    const val STATUS_CONFIRMED = "CONFIRMED"
    const val STATUS_DECLINED = "DECLINED"
    const val STATUS_PENDING = "PENDING"

    data class PlannedEvent(
        val key: String,
        val name: String,
        val description: String,
        val location: String,
        val start: Instant,
        val end: Instant,
        val capacity: Int,
        val publish: Boolean,
    )

    data class PlannedGuest(
        val eventKey: String,
        val firstName: String,
        val lastName: String,
        /** One of [STATUS_CONFIRMED] / [STATUS_DECLINED] / [STATUS_PENDING]. */
        val rsvpStatus: String,
        /** Set when the guest attended: who checked them in… */
        val checkedInBy: String? = null,
        /** …and how long after the event started. */
        val checkedInAfter: Duration? = null,
    ) {
        val email: String = "${firstName.lowercase()}.${lastName.lowercase()}@demo.jiku.example"
    }

    private val galaStart: Instant = Instant.now().plus(Duration.ofDays(30))
    private val conferenceStart: Instant = Instant.now().minus(Duration.ofDays(60))
    private val launchStart: Instant = Instant.now().plus(Duration.ofDays(90))

    /** A draft still being configured, a live event collecting RSVPs, and history. */
    val events =
        listOf(
            PlannedEvent(
                key = EVENT_LAUNCH,
                name = "[DEMO] Product Launch Party",
                description = "Evening launch event — still being configured.",
                location = "Rooftop, Hôtel Ivoire, Abidjan",
                start = launchStart,
                end = launchStart.plus(Duration.ofHours(4)),
                capacity = 80,
                publish = false,
            ),
            PlannedEvent(
                key = EVENT_GALA,
                name = "[DEMO] Annual Gala 2026",
                description = "Black-tie gala with assigned seating.",
                location = "Sofitel Abidjan Hôtel Ivoire",
                start = galaStart,
                end = galaStart.plus(Duration.ofHours(5)),
                capacity = 150,
                publish = true,
            ),
            PlannedEvent(
                key = EVENT_CONFERENCE,
                name = "[DEMO] Spring Conference 2026",
                description = "One-day tech conference — kept as check-in history.",
                location = "Palais de la Culture, Abidjan",
                start = conferenceStart,
                end = conferenceStart.plus(Duration.ofHours(9)),
                capacity = 200,
                publish = true,
            ),
        )

    val guests =
        listOf(
            // Gala: invitations out, answers still coming in.
            PlannedGuest(EVENT_GALA, "Aminata", "Diallo", STATUS_CONFIRMED),
            PlannedGuest(EVENT_GALA, "Kwame", "Mensah", STATUS_CONFIRMED),
            PlannedGuest(EVENT_GALA, "Fatou", "Ndiaye", STATUS_DECLINED),
            PlannedGuest(EVENT_GALA, "Yao", "Kouassi", STATUS_PENDING),
            PlannedGuest(EVENT_GALA, "Mariam", "Traore", STATUS_PENDING),
            PlannedGuest(EVENT_GALA, "Serge", "Bamba", STATUS_PENDING),
            PlannedGuest(EVENT_GALA, "Adjoua", "Koffi", STATUS_PENDING),
            PlannedGuest(EVENT_GALA, "Ibrahim", "Toure", STATUS_PENDING),
            // Conference: most attended (two gates), some no-shows, some declined.
            PlannedGuest(EVENT_CONFERENCE, "Awa", "Cisse", STATUS_CONFIRMED, "Main gate", Duration.ofMinutes(10)),
            PlannedGuest(EVENT_CONFERENCE, "Koffi", "Anan", STATUS_CONFIRMED, "VIP entrance", Duration.ofMinutes(17)),
            PlannedGuest(EVENT_CONFERENCE, "Nadia", "Benali", STATUS_CONFIRMED, "Main gate", Duration.ofMinutes(24)),
            PlannedGuest(EVENT_CONFERENCE, "Moussa", "Keita", STATUS_CONFIRMED, "VIP entrance", Duration.ofMinutes(31)),
            PlannedGuest(EVENT_CONFERENCE, "Chantal", "Aka", STATUS_CONFIRMED, "Main gate", Duration.ofMinutes(38)),
            PlannedGuest(EVENT_CONFERENCE, "Didier", "Zadi", STATUS_CONFIRMED),
            PlannedGuest(EVENT_CONFERENCE, "Salimata", "Coulibaly", STATUS_CONFIRMED),
            PlannedGuest(EVENT_CONFERENCE, "Jean", "Kablan", STATUS_CONFIRMED),
            PlannedGuest(EVENT_CONFERENCE, "Aicha", "Sylla", STATUS_DECLINED),
            PlannedGuest(EVENT_CONFERENCE, "Patrick", "Nguessan", STATUS_DECLINED),
            PlannedGuest(EVENT_CONFERENCE, "Rokia", "Sanogo", STATUS_PENDING),
            PlannedGuest(EVENT_CONFERENCE, "Emmanuel", "Boni", STATUS_PENDING),
        )

    fun confirmedCount(eventKey: String): Int = guests.count { it.eventKey == eventKey && it.rsvpStatus == STATUS_CONFIRMED }

    fun eventStart(eventKey: String): Instant = events.first { it.key == eventKey }.start
}
