package com.jiku.notification.internal

import com.jiku.shared.BookingNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Emails for the booking deposit flow (JIKU-55): a new payment declaration (or a
 * duplicate transaction reference — the region's documented reused-screenshot
 * fraud pattern) alerts the same sales/ops mailbox the manual payment flow uses;
 * a verification outcome notifies the customer directly. These are operational
 * one-off mails, like [ManualPaymentNoticeListener] — there is no per-guest
 * lifecycle to audit here.
 */
@Component
class BookingNoticeListener(
    private val emailSender: EmailSender,
    private val emailProperties: NotificationEmailProperties,
    private val salesProperties: NotificationSalesProperties,
    private val templateRenderer: EmailTemplateRenderer,
) {
    private val log = LoggerFactory.getLogger(BookingNoticeListener::class.java)

    @EventListener
    fun onBookingNotice(notice: BookingNotice) {
        when (notice.kind) {
            BookingNotice.KIND_PAYMENT_DECLARED -> notifySales(notice, duplicate = false)
            BookingNotice.KIND_DUPLICATE_REFERENCE -> notifySales(notice, duplicate = true)
            BookingNotice.KIND_DEPOSIT_VERIFIED, BookingNotice.KIND_BALANCE_VERIFIED, BookingNotice.KIND_PAYMENT_REJECTED ->
                notifyCustomer(notice)
            else -> log.warn("Ignoring booking notice of unknown kind: {}", notice.kind)
        }
    }

    private fun notifySales(
        notice: BookingNotice,
        duplicate: Boolean,
    ) {
        val to = salesProperties.email.takeIf { it.isNotBlank() }
        if (to == null) {
            log.warn(
                "No sales mailbox configured (NOTIFICATION_SALES_EMAIL) — booking payment {} only visible in the admin desk",
                notice.reference,
            )
            return
        }
        deliver(
            EmailMessage(
                to = to,
                toName = "Sales",
                subject =
                    if (duplicate) {
                        "Duplicate transaction reference on booking ${notice.bookingId}"
                    } else {
                        "New booking payment declared — ${notice.customerName}"
                    },
                htmlBody =
                    if (duplicate) {
                        templateRenderer.renderBookingDuplicateReference(notice)
                    } else {
                        templateRenderer.renderBookingPaymentDeclared(notice)
                    },
            ),
        )
    }

    private fun notifyCustomer(notice: BookingNotice) {
        val to = notice.customerEmail.takeIf { it.isNotBlank() }
        if (to == null) {
            log.warn("Booking {} has no customer email to notify", notice.bookingId)
            return
        }
        val (subject, body) =
            when (notice.kind) {
                BookingNotice.KIND_DEPOSIT_VERIFIED ->
                    "Your date is confirmed" to templateRenderer.renderBookingDepositVerified(notice)
                BookingNotice.KIND_BALANCE_VERIFIED ->
                    "Balance received — you're all set" to templateRenderer.renderBookingBalanceVerified(notice)
                else ->
                    "About your payment declaration" to templateRenderer.renderBookingPaymentRejected(notice)
            }
        deliver(EmailMessage(to = to, toName = notice.customerName, subject = subject, htmlBody = body))
    }

    private fun deliver(message: EmailMessage) {
        try {
            emailSender.send(emailProperties.from, message)
        } catch (ex: Exception) {
            // Never let a mail failure roll back the booking state change; the
            // admin desk remains the source of truth.
            log.error("Failed to send booking email to {}", message.to, ex)
        }
    }
}
