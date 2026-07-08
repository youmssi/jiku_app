package com.jiku.invitation.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface GuestErasureLogRepository : JpaRepository<GuestErasureLog, UUID>
