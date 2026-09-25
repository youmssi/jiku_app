package com.jiku.messaging.internal

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

/**
 * Which organization a WhatsApp business number belongs to (ADR 105). A
 * guest's message to an organization's own number reaches the platform
 * webhook with no tenant, so the row is deliberately outside the tenant
 * filter; it holds only the routing. A number belongs to one organization.
 */
@Entity
@Table(name = "whatsapp_business_number")
class WhatsAppBusinessNumber(
    @Id
    @Column(name = "phone_number_id")
    val phoneNumberId: String,
    @Column(name = "tenant_id", nullable = false)
    val tenantId: String,
)

interface WhatsAppBusinessNumberRepository : JpaRepository<WhatsAppBusinessNumber, String> {
    fun deleteByTenantId(tenantId: String)
}
