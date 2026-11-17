package com.jiku.messaging.internal

import com.jiku.live.LiveTicket
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** The notification bell of the organizer space (JIKU-314). */
@RestController
@RequestMapping("/notices")
@PreAuthorize("hasRole('ORGANIZER')")
class OrganizerNoticeController(
    private val notices: OrganizerNoticeService,
) {
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): OrganizerNoticePage = notices.page(page, size)

    @GetMapping("/unread-count")
    fun unread(): UnreadNotices = notices.unread()

    @PostMapping("/{noticeId}/read")
    fun read(
        @PathVariable noticeId: UUID,
    ): OrganizerNoticeView = notices.markRead(noticeId)

    @PostMapping("/read-all")
    fun readAll(): UnreadNotices = notices.markAllRead()

    @GetMapping("/live-ticket")
    fun liveTicket(): LiveTicket = notices.liveTicket()
}
