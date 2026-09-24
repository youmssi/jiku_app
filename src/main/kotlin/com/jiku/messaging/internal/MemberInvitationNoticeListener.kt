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
        mailer.send("member-invitation", notice.email, notice.email, templateRenderer.renderMemberInvitation(notice))
    }
}
