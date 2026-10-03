package com.jiku.messaging.internal

import com.jiku.shared.MemberInvitationNotice
import com.jiku.shared.async.Executors
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Member invitation emails (JIKU-50). Content only — the delivery mechanics live
 * in [OperationalMailer]; a mail failure must not roll back the invitation.
 */
@Component
class MemberInvitationNoticeListener(
    private val mailer: OperationalMailer,
    private val templateRenderer: EmailTemplateRenderer,
) {
    @Async(Executors.URGENT)
    @TransactionalEventListener(fallbackExecution = true)
    fun onMemberInvitationNotice(notice: MemberInvitationNotice) {
        mailer.send("member-invitation", notice.email, notice.email, templateRenderer.renderMemberInvitation(notice))
    }
}
