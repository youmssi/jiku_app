package com.jiku.notification.internal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface WhatsAppPricingRepository : JpaRepository<WhatsAppPricing, UUID> {
    fun findByCategory(category: String): WhatsAppPricing?
}
