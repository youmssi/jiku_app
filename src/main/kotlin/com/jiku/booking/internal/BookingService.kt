package com.jiku.booking.internal

import com.jiku.booking.AdminBookingCancellationView
import com.jiku.booking.AdminBookingRefundView
import com.jiku.booking.AdminBookingView
import com.jiku.booking.AdminPaymentDeclarationView
import com.jiku.catalog.EventModuleApi
import com.jiku.catalog.InvitationChannel
import com.jiku.money.BillingModuleApi
import com.jiku.shared.BookingNotice
import com.jiku.shared.TenantContext
import com.jiku.tenant.TenantModuleApi
import org.springframework.context.ApplicationEventPublisher
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * The deposit-reservation flow (JIKU-55) end to end: quoting a tier and amount
 * from an estimated guest count before any tenant exists, recording Mobile
 * Money payment declarations with a hard guard against reused transaction
 * references, and — once an admin verifies a deposit — provisioning the
 * customer's tenant and a pre-filled draft event so they land in an
 * already-populated workspace, never a blank one.
 */
@Service
class BookingService(
    private val bookings: BookingRepository,
    private val declarations: PaymentDeclarationRepository,
    private val refunds: BookingRefundRepository,
    private val properties: BookingProperties,
    private val billing: BillingModuleApi,
    private val tenantModuleApi: TenantModuleApi,
    private val eventModuleApi: EventModuleApi,
    private val eventPublisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    /** Live tier/deposit preview as a prospect types their guest count — no row is written. */
    fun quote(guestCountEstimate: Long): BookingQuote {
        val tier = billing.tierForGuestCount(guestCountEstimate)
        if (tier == CUSTOM_TIER) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "For estimates beyond 1,000 guests, contact our sales team directly for a custom quote.",
            )
        }
        val totalAmountMinor = billing.priceForTier(tier, guestCountEstimate)
        val depositAmountMinor = roundedShare(totalAmountMinor, properties.depositRate)
        return BookingQuote(
            tier = tier,
            currency = billing.currency(),
            totalAmountMinor = totalAmountMinor,
            depositAmountMinor = depositAmountMinor,
            balanceAmountMinor = totalAmountMinor - depositAmountMinor,
        )
    }

    /**
     * Deliberately not `@Transactional`. A zero-deposit (FREE-tier) booking
     * provisions its tenant immediately, and that provisioning must open a
     * transaction only *after* [TenantContext] is rebound — an ambient
     * transaction opened by a method-level annotation here would already have a
     * Hibernate session bound to no tenant by the time provisioning runs (same
     * reasoning as `ManualPaymentService.resolve`, which makes the same choice).
     */
    fun create(request: CreateBookingRequest): BookingCreationResult {
        val quoted = quote(request.guestCountEstimate)
        val tier = quoted.tier
        val totalAmountMinor = quoted.totalAmountMinor
        val depositAmountMinor = quoted.depositAmountMinor
        val rawToken = BookingTokens.generate()

        val bookingId =
            transactions.execute {
                val booking =
                    Booking(
                        customerName = request.customerName.trim(),
                        customerPhone = request.customerPhone.trim(),
                        customerEmail = request.customerEmail.trim().lowercase(),
                        eventType = request.eventType,
                        eventDate = request.eventDate,
                        guestCountEstimate = request.guestCountEstimate,
                        tier = tier,
                        totalAmountMinor = totalAmountMinor,
                        depositRate = properties.depositRate,
                        depositAmountMinor = depositAmountMinor,
                        balanceAmountMinor = totalAmountMinor - depositAmountMinor,
                        balanceDueDate = request.eventDate.minusDays(properties.balanceDueDaysBeforeEvent),
                        accessTokenHash = BookingTokens.hash(rawToken),
                    )
                booking.acquisitionSource = request.acquisitionSource?.trim()?.takeIf { it.isNotBlank() }
                if (depositAmountMinor <= 0) {
                    booking.status = BookingStatus.DEPOSIT_PAID
                }
                bookings.save(booking)
                requireNotNull(booking.id)
            }
        val id = requireNotNull(bookingId)
        if (depositAmountMinor <= 0) {
            provisionOrganizerAccount(id)
        }
        val saved = requireNotNull(transactions.execute { bookings.findById(id).orElseThrow() })
        return saved.toCreationResult(rawToken, billing.currency())
    }

    @Transactional(readOnly = true)
    fun findByToken(
        bookingId: UUID,
        rawToken: String,
    ): BookingStatusView = requireBookingByToken(bookingId, rawToken).toStatusView(billing.currency())

    @Transactional
    fun declarePayment(
        bookingId: UUID,
        rawToken: String,
        request: DeclarePaymentRequest,
    ): PaymentDeclarationResult {
        val booking = requireBookingByToken(bookingId, rawToken)
        val reference = request.transactionReference.trim()
        val isDuplicate =
            declarations.existsByTransactionReferenceAndVerificationStatusNot(reference, PaymentVerificationStatus.DUPLICATE)
        val declaration =
            PaymentDeclaration(
                bookingId = requireNotNull(booking.id),
                amountMinor = request.amountMinor,
                kind = request.kind,
                operator = request.operator,
                transactionReference = reference,
            )
        if (isDuplicate) {
            declaration.verificationStatus = PaymentVerificationStatus.DUPLICATE
        }
        val saved =
            try {
                declarations.saveAndFlush(declaration)
            } catch (ex: DataIntegrityViolationException) {
                // A concurrent declaration claimed this exact reference between our
                // check and this insert — rare, and the partial unique index is what
                // actually prevents the dangerous outcome (two valid declarations
                // sharing one reference); this response is the honest fallback.
                throw ResponseStatusException(HttpStatus.CONFLICT, "This transaction reference was just claimed by another declaration", ex)
            }
        eventPublisher.publishEvent(
            BookingNotice(
                kind = if (isDuplicate) BookingNotice.KIND_DUPLICATE_REFERENCE else BookingNotice.KIND_PAYMENT_DECLARED,
                bookingId = requireNotNull(booking.id),
                customerName = booking.customerName,
                customerEmail = booking.customerEmail,
                customerPhone = booking.customerPhone,
                reference = saved.transactionReference,
                amountMinor = saved.amountMinor,
                currency = billing.currency(),
                declarationKind = saved.kind.name,
            ),
        )
        return saved.toResult()
    }

    @Transactional(readOnly = true)
    fun adminListBookings(
        status: String?,
        page: Int,
        size: Int,
    ): List<AdminBookingView> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val results =
            if (normalized == null) {
                bookings.findAllByOrderByCreatedAtDesc(pageable)
            } else {
                val parsed =
                    runCatching { BookingStatus.valueOf(normalized) }
                        .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status") }
                bookings.findByStatusOrderByCreatedAtDesc(parsed, pageable)
            }
        return results.map { it.toAdminView(billing.currency()) }
    }

    @Transactional
    fun adminCancelBooking(bookingId: UUID): AdminBookingCancellationView {
        val booking =
            bookings.findById(bookingId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found") }
        if (booking.status == BookingStatus.CANCELLED || booking.status == BookingStatus.REFUNDED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This booking is already ${booking.status.name.lowercase()}")
        }
        val refund = RefundPolicy.refundAmountMinor(booking.depositAmountMinor, LocalDate.now(), booking.eventDate)
        booking.status = BookingStatus.CANCELLED
        booking.updatedAt = Instant.now()
        bookings.save(booking)
        return AdminBookingCancellationView(
            id = requireNotNull(booking.id),
            status = booking.status.name,
            refundAmountMinor = refund,
            currency = billing.currency(),
        )
    }

    /**
     * Enregistre le remboursement exécuté (JIKU-75) contre l'acompte vérifié
     * d'origine : montant partiel possible (jamais au-delà du restant de
     * l'acompte), motif obligatoire, avoir CREDIT_NOTE émis sous le tenant de
     * l'organisateur, client notifié, réservation passée à REFUNDED.
     */
    @Transactional
    fun adminRefundBooking(
        bookingId: UUID,
        amountMinor: Long,
        reason: String,
    ): AdminBookingRefundView {
        if (amountMinor <= 0) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A refund amount must be positive")
        }
        val booking =
            bookings.findById(bookingId).orElseThrow {
                ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found")
            }
        if (booking.status == BookingStatus.REFUNDED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This booking is already refunded")
        }
        if (booking.status != BookingStatus.CANCELLED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Cancel the booking before refunding it")
        }
        val deposit =
            declarations.findFirstByBookingIdAndKindAndVerificationStatusOrderByDeclaredAtDesc(
                bookingId,
                PaymentDeclarationKind.DEPOSIT,
                PaymentVerificationStatus.VERIFIED,
            ) ?: throw ResponseStatusException(HttpStatus.CONFLICT, "No settled deposit to refund")
        val alreadyRefunded = refunds.findByDeclarationId(requireNotNull(deposit.id)).sumOf { it.amountMinor }
        if (amountMinor > deposit.amountMinor - alreadyRefunded) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "Refund exceeds the remaining deposit (${deposit.amountMinor - alreadyRefunded})",
            )
        }
        val tenantId =
            booking.tenantId
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "This booking has no organizer tenant yet")
        val trimmedReason = reason.trim()
        if (trimmedReason.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "A refund reason is required")
        }

        val refund =
            BookingRefund(
                bookingId = bookingId,
                declarationId = requireNotNull(deposit.id),
                amountMinor = amountMinor,
                currency = billing.currency(),
                reason = trimmedReason,
            )
        refunds.save(refund)

        // L'avoir se numérote dans le tenant de l'organisateur (REQUIRES_NEW côté
        // money) : on lie ce tenant avant l'appel, comme pour les autres écritures
        // du module money lancées hors requête organisateur.
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            val doc =
                billing.issueBookingAvoir(
                    customerName = booking.customerName,
                    customerCountry = properties.refundCountry,
                    amountMinor = amountMinor,
                    currency = billing.currency(),
                    description = "Remboursement d'acompte — ${booking.eventType} du ${booking.eventDate}",
                )
            refund.creditNoteId = doc.invoiceId
            refund.creditNoteNumber = doc.invoiceNumber
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }

        booking.status = BookingStatus.REFUNDED
        booking.updatedAt = Instant.now()
        bookings.save(booking)

        eventPublisher.publishEvent(
            BookingNotice(
                kind = BookingNotice.KIND_REFUNDED,
                bookingId = bookingId,
                customerName = booking.customerName,
                customerEmail = booking.customerEmail,
                customerPhone = booking.customerPhone,
                reference = refund.creditNoteNumber,
                amountMinor = amountMinor,
                currency = billing.currency(),
                note = null,
            ),
        )
        return AdminBookingRefundView(
            id = requireNotNull(refund.id),
            bookingId = bookingId,
            amountMinor = amountMinor,
            currency = billing.currency(),
            reason = trimmedReason,
            creditNoteNumber = refund.creditNoteNumber,
            status = booking.status.name,
        )
    }

    @Transactional(readOnly = true)
    fun adminListPaymentDeclarations(
        status: String?,
        page: Int,
        size: Int,
    ): List<AdminPaymentDeclarationView> {
        val pageable = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_PAGE_SIZE))
        val normalized = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        val results =
            if (normalized == null) {
                declarations.findAllByOrderByDeclaredAtDesc(pageable)
            } else {
                val parsed =
                    runCatching { PaymentVerificationStatus.valueOf(normalized) }
                        .getOrElse { throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown status: $status") }
                declarations.findByVerificationStatusOrderByDeclaredAtDesc(parsed, pageable)
            }
        return results.map { declaration ->
            val customerName = bookings.findById(declaration.bookingId).orElse(null)?.customerName ?: "Unknown"
            declaration.toAdminView(customerName, billing.currency())
        }
    }

    /**
     * Deliberately not `@Transactional` — see [create] and [provisionOrganizerAccount].
     */
    fun adminVerifyPaymentDeclaration(
        declarationId: UUID,
        adminId: String,
    ): AdminPaymentDeclarationView {
        val outcome =
            requireNotNull(
                transactions.execute {
                    val declaration =
                        declarations.findById(declarationId).orElseThrow {
                            ResponseStatusException(HttpStatus.NOT_FOUND, "Declaration not found")
                        }
                    if (declaration.verificationStatus != PaymentVerificationStatus.PENDING) {
                        throw ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "This declaration is already ${declaration.verificationStatus.name.lowercase()}",
                        )
                    }
                    val booking =
                        bookings.findById(declaration.bookingId).orElseThrow {
                            ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found")
                        }
                    declaration.verificationStatus = PaymentVerificationStatus.VERIFIED
                    declaration.verifiedBy = adminId
                    declaration.verifiedAt = Instant.now()
                    declarations.save(declaration)

                    val transitionsToDepositPaid =
                        declaration.kind == PaymentDeclarationKind.DEPOSIT && booking.status == BookingStatus.AWAITING_DEPOSIT
                    when (declaration.kind) {
                        PaymentDeclarationKind.DEPOSIT -> if (transitionsToDepositPaid) booking.status = BookingStatus.DEPOSIT_PAID
                        PaymentDeclarationKind.BALANCE -> booking.status = BookingStatus.FULLY_PAID
                    }
                    booking.updatedAt = Instant.now()
                    bookings.save(booking)
                    VerificationOutcome(declaration, booking, transitionsToDepositPaid)
                },
            )
        if (outcome.provisionsTenant) {
            provisionOrganizerAccount(requireNotNull(outcome.booking.id))
        } else if (outcome.declaration.kind == PaymentDeclarationKind.BALANCE) {
            recordPrepayment(outcome.booking, outcome.declaration.amountMinor)
        }
        val finalBooking = requireNotNull(transactions.execute { bookings.findById(requireNotNull(outcome.booking.id)).orElseThrow() })
        val noticeKind =
            if (outcome.declaration.kind ==
                PaymentDeclarationKind.DEPOSIT
            ) {
                BookingNotice.KIND_DEPOSIT_VERIFIED
            } else {
                BookingNotice.KIND_BALANCE_VERIFIED
            }
        publishVerificationNotice(finalBooking, outcome.declaration, noticeKind, note = null)
        return outcome.declaration.toAdminView(finalBooking.customerName, billing.currency())
    }

    @Transactional
    fun adminRejectPaymentDeclaration(
        declarationId: UUID,
        adminId: String,
        reason: String,
    ): AdminPaymentDeclarationView {
        val declaration =
            declarations.findById(declarationId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Declaration not found") }
        if (declaration.verificationStatus != PaymentVerificationStatus.PENDING) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "This declaration is already ${declaration.verificationStatus.name.lowercase()}",
            )
        }
        val booking =
            bookings.findById(declaration.bookingId).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found") }
        declaration.verificationStatus = PaymentVerificationStatus.REJECTED
        declaration.verifiedBy = adminId
        declaration.verifiedAt = Instant.now()
        declaration.rejectionReason = reason
        declarations.save(declaration)
        publishVerificationNotice(booking, declaration, BookingNotice.KIND_PAYMENT_REJECTED, note = reason)
        return declaration.toAdminView(booking.customerName, billing.currency())
    }

    /**
     * Provisions the organizer's tenant + owner account (not tenant-scoped, so
     * safe under any ambient context), then rebinds [TenantContext] to the
     * fresh tenant and opens a **new** transaction to create the pre-filled
     * draft event and unlock its tier. The tenant filter resolves once when a
     * Hibernate session opens, so that session must not exist before the
     * context is rebound — the same reasoning `ManualPaymentService.resolve`
     * applies for its own cross-tenant admin action.
     */
    private fun provisionOrganizerAccount(bookingId: UUID) {
        val booking = requireNotNull(transactions.execute { bookings.findById(bookingId).orElseThrow() })
        val tenantId = tenantModuleApi.provisionTenant(booking.customerName, booking.customerEmail, booking.customerName)
        val previous = TenantContext.get()
        TenantContext.set(tenantId.toString())
        try {
            transactions.execute {
                val eventId =
                    eventModuleApi.createDraftEvent(
                        name = defaultEventName(booking),
                        timezone = properties.eventTimezone,
                        startDateTime = booking.eventDate.atStartOfDay(ZoneId.of(properties.eventTimezone)).toInstant(),
                        invitationChannels = setOf(InvitationChannel.EMAIL),
                    )
                billing.unlockTier(eventId, booking.tier)
                // The deposit already paid offsets the event's *next* tier upgrade
                // (JIKU-57), for a booking whose real guest count outgrows its estimate.
                billing.recordPrepayment(eventId, booking.depositAmountMinor)
                val fresh = bookings.findById(bookingId).orElseThrow()
                fresh.tenantId = tenantId.toString()
                fresh.eventId = eventId
                fresh.updatedAt = Instant.now()
                bookings.save(fresh)
            }
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    /**
     * Records a balance payment as prepaid credit against the booking's event
     * (JIKU-57) — same rebind-then-fresh-transaction reasoning as
     * [provisionOrganizerAccount], since [com.jiku.money.internal.UsageRecord]
     * is tenant-scoped. A no-op if the booking has no event yet, which should
     * not happen for a balance (the deposit already provisioned one).
     */
    private fun recordPrepayment(
        booking: Booking,
        amountMinor: Long,
    ) {
        val tenantId = booking.tenantId ?: return
        val eventId = booking.eventId ?: return
        val previous = TenantContext.get()
        TenantContext.set(tenantId)
        try {
            transactions.execute { billing.recordPrepayment(eventId, amountMinor) }
        } finally {
            if (previous != null) TenantContext.set(previous) else TenantContext.clear()
        }
    }

    private fun defaultEventName(booking: Booking): String {
        val type =
            booking.eventType.name
                .lowercase()
                .replaceFirstChar { it.uppercase() }
        return "$type — ${booking.customerName}"
    }

    private fun publishVerificationNotice(
        booking: Booking,
        declaration: PaymentDeclaration,
        kind: String,
        note: String?,
    ) {
        eventPublisher.publishEvent(
            BookingNotice(
                kind = kind,
                bookingId = requireNotNull(booking.id),
                customerName = booking.customerName,
                customerEmail = booking.customerEmail,
                customerPhone = booking.customerPhone,
                reference = declaration.transactionReference,
                amountMinor = declaration.amountMinor,
                currency = billing.currency(),
                balanceAmountMinor = booking.balanceAmountMinor.takeIf { kind == BookingNotice.KIND_DEPOSIT_VERIFIED },
                balanceDueDate = booking.balanceDueDate.toString().takeIf { kind == BookingNotice.KIND_DEPOSIT_VERIFIED },
                note = note,
            ),
        )
    }

    private fun requireBookingByToken(
        bookingId: UUID,
        rawToken: String,
    ): Booking {
        val booking =
            bookings.findById(bookingId).orElse(null)
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found")
        if (booking.accessTokenHash != BookingTokens.hash(rawToken)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found")
        }
        return booking
    }

    private fun roundedShare(
        amountMinor: Long,
        rate: BigDecimal,
    ): Long = BigDecimal(amountMinor).multiply(rate).setScale(0, RoundingMode.HALF_UP).toLong()

    private data class VerificationOutcome(
        val declaration: PaymentDeclaration,
        val booking: Booking,
        val provisionsTenant: Boolean,
    )

    private companion object {
        const val CUSTOM_TIER = "CUSTOM"
        const val MAX_PAGE_SIZE = 100
    }
}
