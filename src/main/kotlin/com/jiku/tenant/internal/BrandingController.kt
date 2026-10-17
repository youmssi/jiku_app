package com.jiku.tenant.internal

import com.jiku.shared.TenantContext
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.util.UUID

@RestController
@RequestMapping("/branding")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class BrandingController(
    private val brandingService: BrandingService,
) {
    @GetMapping
    fun get(): BrandingResponse = brandingService.getBranding(currentTenantId())

    @PutMapping
    fun update(
        @Valid @RequestBody request: UpdateBrandingRequest,
    ): BrandingResponse = brandingService.updateBranding(currentTenantId(), request)

    /** Uploads the logo or the banner (JIKU-259); `kind` is `logo` or `banner`. */
    @PutMapping("/{kind}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun replaceImage(
        @PathVariable kind: String,
        @RequestPart("file") file: MultipartFile,
    ): BrandingResponse = brandingService.replaceImage(currentTenantId(), tenantImageKindOf(kind), file.bytes, file.contentType)

    @DeleteMapping("/{kind}")
    fun removeImage(
        @PathVariable kind: String,
    ): BrandingResponse = brandingService.removeImage(currentTenantId(), tenantImageKindOf(kind))

    private fun currentTenantId(): UUID {
        val tenantId =
            TenantContext.get()
                ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No tenant context")
        return UUID.fromString(tenantId)
    }
}

/** The organization's logo and banner as public pages, emails and link previews fetch them. */
@RestController
@RequestMapping("/public/orgs/{tenantId}/images")
class PublicTenantImageController(
    private val brandingService: BrandingService,
) {
    @GetMapping("/{kind}")
    fun image(
        @PathVariable tenantId: UUID,
        @PathVariable kind: String,
    ): ResponseEntity<ByteArray> {
        val image = brandingService.publicImage(tenantId, tenantImageKindOf(kind))
        return ResponseEntity
            .ok()
            .contentType(MediaType.parseMediaType(image.contentType))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable())
            .body(image.content)
    }
}
