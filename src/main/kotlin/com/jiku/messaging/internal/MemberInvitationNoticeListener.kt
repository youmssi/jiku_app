package com.jiku.messaging.internal

import com.jiku.shared.MemberInvitationNotice
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * Member invitation emails (JIKU-50). Content only — the delivery mechanics live
 * in [OperationalMailer]; a mail failure must not roll back the invitation.
 */
@Component
class MemberInvitationNoticeListener(
    private val mailer: OperationalMailer,
    private val templateRenderer: EmailTemplateRenderer,
) {
    @EventListener
    fun onMemberInvitationNotice(notice: MemberInvitationNotice) {
        mailer.sendOperationalHtml(
            label = "member-invitation",
            to = notice.email,
            toName = notice.email,
            subject = "You've been invited to join ${notice.organizationName} on Jikū",
            htmlBody = templateRenderer.renderMemberInvitation(notice),
        )
    }
}
