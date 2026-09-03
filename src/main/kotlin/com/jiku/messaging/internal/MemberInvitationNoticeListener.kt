package com.jiku.messaging.internal

import com.jiku.shared.MemberInvitationNotice
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Member invitation emails (JIKU-50) — operational one-off mails like the
 * account notices, sent through the platform sender directly.
 */
@Component
class MemberInvitationNoticeListener(
    private val emailSender: EmailSender,
    private val emailProperties: NotificationEmailProperties,
    private val templateRenderer: EmailTemplateRenderer,
) {
    private val log = LoggerFactory.getLogger(MemberInvitationNoticeListener::class.java)

    @EventListener
    fun onMemberInvitationNotice(notice: MemberInvitationNotice) {
        try {
            emailSender.send(
                emailProperties.from,
                EmailMessage(
                    to = notice.email,
                    toName = notice.email,
                    subject = "You've been invited to join ${notice.organizationName} on Jikū",
                    htmlBody = templateRenderer.renderMemberInvitation(notice),
                ),
            )
        } catch (ex: Exception) {
            // A mail failure must not roll back the invitation; the manager can
            // simply re-invite, which refreshes the token and resends.
            log.error("Failed to send member invitation email to {}", notice.email, ex)
        }
    }
}
