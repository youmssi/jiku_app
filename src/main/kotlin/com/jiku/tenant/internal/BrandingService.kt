package com.jiku.tenant.internal

import com.jiku.shared.ApiProperties
import com.jiku.shared.HTTPS_IMAGE_URL
import com.jiku.shared.ImageRules
import com.jiku.shared.acceptUploadedImage
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.UUID

@Service
class BrandingService(
    private val tenants: TenantRepository,
    private val images: TenantImageRepository,
    private val imageProperties: TenantImageProperties,
    private val api: ApiProperties,
) {
    @Transactional(readOnly = true)
    fun getBranding(tenantId: UUID): BrandingResponse = loadTenant(tenantId).toBrandingResponse()

    @Transactional
    fun updateBranding(
        tenantId: UUID,
        request: UpdateBrandingRequest,
    ): BrandingResponse {
        val tenant = loadTenant(tenantId)
        val branding = tenant.ensureBranding()
        branding.displayName = request.displayName?.takeIf { it.isNotBlank() }
        branding.logoUrl = imageLink(request.logoUrl, branding.logoUrl)
        branding.bannerUrl = imageLink(request.bannerUrl, branding.bannerUrl)
        branding.primaryColor = request.primaryColor?.takeIf { it.isNotBlank() }
        tenants.save(tenant)
        return tenant.toBrandingResponse()
    }

    /**
     * The link to keep for an image (JIKU-321): blank clears it, the link already
     * stored is kept as sent back, and a new one must be `https://`, since the site
     * fetches it to draw cards and must never be led to an internal address.
     */
    private fun imageLink(
        requested: String?,
        current: String?,
    ): String? {
        val link = requested?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (link == current || HTTPS_IMAGE.matches(link)) return link
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The image must be an https:// address")
    }

    /**
     * Stores an uploaded logo or banner (JIKU-259) and points the branding at its
     * public link, so every page and email that shows the logo or banner picks it
     * up without knowing it was uploaded rather than linked.
     */
    @Transactional
    fun replaceImage(
        tenantId: UUID,
        kind: TenantImageKind,
        content: ByteArray,
        contentType: String?,
        now: Instant = Instant.now(),
    ): BrandingResponse {
        val tenant = loadTenant(tenantId)
        val type = acceptUploadedImage(content, contentType, rulesFor(kind))
        val image =
            images.findByKind(kind)?.apply {
                this.content = content
                this.contentType = type
                updatedAt = now
            } ?: TenantImage(kind = kind, content = content, contentType = type, updatedAt = now)
        images.save(image)
        val url = "${imageProperties.apiPublicUrl}${api.basePath}/public/orgs/$tenantId/images/${kind.path}?v=${now.epochSecond}"
        tenant.ensureBranding().apply {
            when (kind) {
                TenantImageKind.LOGO -> logoUrl = url
                TenantImageKind.BANNER -> bannerUrl = url
            }
        }
        tenants.save(tenant)
        return tenant.toBrandingResponse()
    }

    @Transactional
    fun removeImage(
        tenantId: UUID,
        kind: TenantImageKind,
    ): BrandingResponse {
        val tenant = loadTenant(tenantId)
        images.findByKind(kind)?.let { images.delete(it) }
        tenant.ensureBranding().apply {
            when (kind) {
                TenantImageKind.LOGO -> logoUrl = null
                TenantImageKind.BANNER -> bannerUrl = null
            }
        }
        tenants.save(tenant)
        return tenant.toBrandingResponse()
    }

    @Transactional(readOnly = true)
    fun publicImage(
        tenantId: UUID,
        kind: TenantImageKind,
    ): PublicTenantImage =
        images.findPublic(tenantId.toString(), kind.name)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such photo")

    private fun rulesFor(kind: TenantImageKind): ImageRules =
        when (kind) {
            TenantImageKind.LOGO ->
                ImageRules(imageProperties.maxBytes, imageProperties.logoMinSize, imageProperties.logoMinSize)
            TenantImageKind.BANNER ->
                ImageRules(imageProperties.maxBytes, imageProperties.bannerMinWidth, imageProperties.bannerMinHeight)
        }

    private fun loadTenant(tenantId: UUID): Tenant =
        tenants.findById(tenantId).orElseThrow {
            ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found")
        }
}

private val HTTPS_IMAGE = Regex(HTTPS_IMAGE_URL)

/** The photo's segment in its public link. */
val TenantImageKind.path: String get() = name.lowercase()

fun tenantImageKindOf(path: String): TenantImageKind =
    TenantImageKind.entries.firstOrNull { it.path == path }
        ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No such photo")
