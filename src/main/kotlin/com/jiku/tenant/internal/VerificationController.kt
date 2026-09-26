package com.jiku.tenant.internal

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException

data class PhoneCodeRequest(
    @field:NotBlank @field:Size(max = 32) val phone: String,
)

data class PhoneConfirmRequest(
    @field:NotBlank @field:Size(max = 12) val code: String,
)

/**
 * The organization's verification (JIKU-175): where it stands, the phone
 * confirmation, and the personal or company request with its documents.
 * Managers only, like payment methods, which verification unlocks.
 */
@RestController
@RequestMapping("/settings/verification")
@PreAuthorize("hasRole('ORGANIZER_MANAGER')")
class VerificationController(
    private val verification: VerificationService,
) {
    @GetMapping
    fun overview(): VerificationOverview = verification.overview()

    @PostMapping("/phone")
    fun requestPhoneCode(
        @Valid @RequestBody request: PhoneCodeRequest,
    ): PhoneCodeSent = verification.requestPhoneCode(request.phone)

    @PostMapping("/phone/confirm")
    fun confirmPhone(
        @Valid @RequestBody request: PhoneConfirmRequest,
    ): PhoneStatusView = verification.confirmPhone(request.code)

    /** Submits a personal or company request; `kind` is `personal` or `company`. */
    @PostMapping("/{kind}", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun submit(
        @PathVariable kind: String,
        @RequestParam legalName: String,
        @RequestParam documentType: String,
        @RequestParam(required = false) registrationNumber: String?,
        @RequestParam(required = false) taxIdentifier: String?,
        @RequestPart("files") files: List<MultipartFile>,
    ): VerificationRequestView =
        verification.submit(
            VerificationSubmission(
                kind = parseKind(kind),
                legalName = legalName,
                documentType = documentType,
                registrationNumber = registrationNumber,
                taxIdentifier = taxIdentifier,
            ),
            files.map { UploadedDocument(it.bytes) },
        )

    private fun parseKind(kind: String): VerificationKind =
        when (kind.lowercase()) {
            "personal" -> VerificationKind.PERSONAL
            "company" -> VerificationKind.COMPANY
            else -> throw ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown verification kind: $kind")
        }
}
