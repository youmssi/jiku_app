package com.jiku.catalog.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ServiceConfigRepository : JpaRepository<ServiceConfig, UUID>
