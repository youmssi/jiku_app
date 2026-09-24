package com.jiku.ticket

import com.jayway.jsonpath.JsonPath
import com.jiku.TestcontainersConfiguration
import com.jiku.catalog.ResourceType
import com.jiku.catalog.internal.ConfirmationMode
import com.jiku.catalog.internal.Resource
import com.jiku.catalog.internal.ResourceAvailability
import com.jiku.catalog.internal.ResourceAvailabilityRepository
import com.jiku.catalog.internal.ResourceRepository
import com.jiku.catalog.internal.ServiceAdminService
import com.jiku.catalog.internal.ServiceConfigService
import com.jiku.catalog.internal.ServiceConfigUpdate
import com.jiku.catalog.internal.ServiceCreateRequest
import com.jiku.catalog.internal.SlotEngine
import com.jiku.messaging.internal.NotificationLog
import com.jiku.messaging.internal.NotificationLogRepository
import com.jiku.messaging.internal.WhatsAppMessageCostRepository
import com.jiku.shared.ReminderChannel
import com.jiku.shared.TenantContext
import com.jiku.ticket.internal.AppointmentReminderRepository
import com.jiku.ticket.internal.AppointmentReminderSweep
import com.jiku.ticket.internal.ReminderStatus
import com.jiku.ticket.internal.TicketKind
import com.jiku.ticket.internal.TicketRepository
import com.jiku.ticket.internal.TicketStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rappels de rendez-vous (JIKU-89) — DoD : deux exécutions du balayage n'envoient
 * qu'un rappel ; un rendez-vous annulé entre la programmation et l'envoi ne
 * déclenche aucun rappel. Le balayage est appelé avec un « maintenant » figé pour
 * rester déterministe ; l'infrastructure d'envoi est le transport `log`.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AppointmentReminderTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var engine: SlotEngine

    @Autowired
    lateinit var services: ServiceAdminService

    @Autowired
    lateinit var resources: ResourceRepository

    @Autowired
    lateinit var availabilities: ResourceAvailabilityRepository

    @Autowired
    lateinit var configService: ServiceConfigService

    @Autowired
    lateinit var sweep: AppointmentReminderSweep

    @Autowired
    lateinit var reminders: AppointmentReminderRepository

    @Autowired
    lateinit var logs: NotificationLogRepository

    @Autowired
    lateinit var costs: WhatsAppMessageCostRepository

    @Autowired
    lateinit var tickets: TicketRepository

    @Autowired
    lateinit var linkTokens: com.jiku.catalog.internal.ServiceLinkTokenService

    private var cleanup: Pair<String, UUID>? = null

    @AfterEach
    fun cleanUp() {
        cleanup?.let { (tenant, serviceId) ->
            TenantContext.set(tenant)
            configService.update(serviceId, ServiceConfigUpdate(reminderChannel = ReminderChannel.NONE))
            reminders.findAll().forEach { reminders.delete(it) }
            TenantContext.clear()
        }
        // Le compteur de fenêtre WhatsApp est global (lecture native) : les lignes
        // laissées par un test pousseraient le suivant au-dessus du seuil.
        costs.deleteAll()
        TenantContext.clear()
    }

    @Test
    fun `two sweep executions send a reminder exactly once`() {
        val serviceId = seedService("reminder-once")

        TenantContext.set("reminder-once")
        val slot = Instant.parse("2026-11-02T09:00:00Z")
        engine.bookClient(serviceId, slot, "Fatou", "+224600000060")

        val now = Instant.parse("2026-11-02T07:40:00Z")
        sweep.sweepService(serviceId, "WHATSAPP", "120", "reminder-once", "Africa/Conakry", now)
        sweep.sweepService(serviceId, "WHATSAPP", "120", "reminder-once", "Africa/Conakry", now)

        val rows = reminders.findAll()
        assertEquals(1, rows.size, "un seul rappel réservé pour (billet, décalage)")
        val row = rows.single()
        assertEquals(ReminderStatus.SENT, row.status)
        assertEquals(1, row.attempts)

        val sent =
            logs.findByReferenceId(requireNotNull(row.id)).count { it.status == NotificationLog.STATUS_SENT }
        assertEquals(1, sent, "un seul envoi journalisé malgré deux exécutions")
    }

    @Test
    fun `a cancelled appointment triggers no reminder`() {
        val serviceId = seedService("reminder-cancelled")
        TenantContext.set("reminder-cancelled")
        val slot = Instant.parse("2026-11-02T09:00:00Z")
        val linkToken = linkTokens.issue(serviceId, "reminder-cancelled")
        TenantContext.clear()

        // Le client réserve puis annule dans le délai, comme en production.
        val booked =
            mockMvc
                .perform(
                    post("/api/v1/appointments/$linkToken/book")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """{"clientName":"Aminata","clientPhone":"+224600000061","startsAt":"$slot"}""",
                        ),
                ).andExpect(status().isCreated())
                .andReturn()
                .response.contentAsString
        val bookingToken = JsonPath.read<String>(booked, "$.bookingToken")

        // Le client consulte sa réservation (le jeton résout bien les lignes).
        mockMvc
            .perform(get("/api/v1/appointments/$linkToken/booking/$bookingToken"))
            .andExpect(status().isOk())

        mockMvc
            .perform(delete("/api/v1/appointments/$linkToken/booking/$bookingToken"))
            .andExpect(status().isNoContent())

        // Le billet du rendez-vous est annulé : plus rappelable.
        TenantContext.set("reminder-cancelled")
        val cancelled =
            tickets.findByServiceIdAndStartsAtAndStatusAndKind(
                serviceId,
                slot,
                TicketStatus.CANCELLED,
                TicketKind.APPOINTMENT,
            )
        assertEquals(1, cancelled.size)

        val now = Instant.parse("2026-11-02T07:40:00Z")
        sweep.sweepService(serviceId, "WHATSAPP", "120", "reminder-cancelled", "Africa/Conakry", now)
        sweep.sweepService(serviceId, "WHATSAPP", "120", "reminder-cancelled", "Africa/Conakry", now)
        assertTrue(reminders.findAll().isEmpty(), "aucun rappel pour un rendez-vous annulé")
    }

    private fun seedService(tenant: String): UUID {
        TenantContext.set(tenant)
        val resource = resources.save(Resource(name = "Coiffeuse", type = ResourceType.PERSON, timezone = "Africa/Conakry"))
        availabilities.save(
            ResourceAvailability(
                resourceId = requireNotNull(resource.id),
                dayOfWeek = 1,
                start = LocalTime.of(9, 0),
                end = LocalTime.of(13, 0),
            ),
        )
        val service = services.create(ServiceCreateRequest(name = "Coupe", timezone = "Africa/Conakry"))
        services.addRequirement(service.id, ResourceType.PERSON, 1)
        configService.update(
            service.id,
            ServiceConfigUpdate(
                confirmationMode = ConfirmationMode.INSTANTANEOUS,
                maxHorizonDays = 365,
                reminderChannel = ReminderChannel.WHATSAPP,
                reminderOffsetsMinutes = listOf(120),
            ),
        )
        cleanup = tenant to requireNotNull(service.id)
        return service.id
    }
}
