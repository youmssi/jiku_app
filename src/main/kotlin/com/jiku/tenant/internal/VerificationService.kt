package com.jiku.tenant.internal

import com.jiku.shared.PhoneCodeRequested
import com.jiku.shared.TenantContext
import com.jiku.shared.VerificationGate
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Limits of the verification flow; every one is configuration. */
@ConfigurationProperties(prefix = "verification")
data class VerificationProperties(
    /** Time the documents of an approved request are kept before deletion. */
    val documentRetention: Duration = Duration.ofDays(30),
    val maxFileBytes: Int = 2 * 1024 * 1024,
    val maxFiles: Int = 3,
    val codeTtl: Duration = Duration.ofMinutes(10),
    /** Codes one organization may request per [codeWindow]. */
    val maxCodesPerWindow: Int = 5,
    val codeWindow: Duration = Duration.ofHours(1),
    /** Wrong guesses allowed on one code before a new one is needed. */
    val maxCodeAttempts: Int = 5,
)

/** One uploaded file, as the controller received it. */
class UploadedDocument(
    val bytes: ByteArray,
)

/** What an organization submits for one verification. */
data class VerificationSubmission(
    val kind: VerificationKind,
    val legalName: String,
    val documentType: String,
    val registrationNumber: String? = null,
    val taxIdentifier: String? = null,
)

data class VerificationRequestView(
    val id: UUID,
    val kind: VerificationKind,
    val status: VerificationStatus,
    val legalName: String,
    val documentType: String,
    val submittedAt: Instant,
    val decidedAt: Instant?,
    val rejectionReason: String?,
)

data class PhoneStatusView(
    val phone: String?,
    val verified: Boolean,
)

/** Where the organization stands: whether it may take payments, and its latest request of each kind. */
data class VerificationOverview(
    val verified: Boolean,
    val personal: VerificationRequestView?,
    val company: VerificationRequestView?,
    val phone: PhoneStatusView,
    /** Upload limits of a request, so the client can prepare files before sending them. */
    val limits: VerificationLimits,
)

data class VerificationLimits(
    val maxFiles: Int,
    val maxFileBytes: Int,
)

data class PhoneCodeSent(
    val expiresAt: Instant,
)

/**
 * Organization verification (JIKU-175, référentiel métier §9), for the calling
 * tenant. A personal request needs a confirmed phone and an identity document;
 * a company request needs the business's papers. Documents are checked by their
 * content (JPEG, PNG or PDF), never by the name or type the browser claims.
 */
@Service
class VerificationService(
    private val verifications: OrganizationVerificationRepository,
    private val documents: VerificationDocumentRepository,
    private val phones: PhoneVerificationRepository,
    private val store: DocumentStore,
    private val properties: VerificationProperties,
    private val events: ApplicationEventPublisher,
) : VerificationGate {
    private val random = SecureRandom()

    @Transactional(readOnly = true)
    override fun verifiedKind(): String? {
        if (TenantContext.get() == null) return null
        val approved = verifications.findAll().filter { it.status == VerificationStatus.APPROVED }.map { it.kind }
        return when {
            VerificationKind.COMPANY in approved -> VerificationKind.COMPANY.name
            VerificationKind.PERSONAL in approved -> VerificationKind.PERSONAL.name
            else -> null
        }
    }

    @Transactional(readOnly = true)
    fun overview(): VerificationOverview {
        val all = verifications.findAll().sortedByDescending { it.submittedAt }
        val phone = phones.findCurrent().firstOrNull()
        return VerificationOverview(
            verified = all.any { it.status == VerificationStatus.APPROVED },
            personal = all.firstOrNull { it.kind == VerificationKind.PERSONAL }?.toView(),
            company = all.firstOrNull { it.kind == VerificationKind.COMPANY }?.toView(),
            phone = PhoneStatusView(phone = phone?.phone, verified = phone?.verifiedAt != null),
            limits = VerificationLimits(maxFiles = properties.maxFiles, maxFileBytes = properties.maxFileBytes),
        )
    }

    @Transactional
    fun requestPhoneCode(
        rawPhone: String,
        now: Instant = Instant.now(),
    ): PhoneCodeSent {
        val phone = rawPhone.filterNot { it == ' ' }
        if (!PHONE.matches(phone)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Use the international format, e.g. +224620000000")
        }
        val tenantId = requireNotNull(TenantContext.get())
        val code = (0 until CODE_LENGTH).joinToString("") { random.nextInt(10).toString() }
        val expiresAt = now.plus(properties.codeTtl)
        val row = phones.findCurrent().firstOrNull()
        val record =
            if (row == null) {
                PhoneVerification(phone, hash(tenantId, phone, code), expiresAt, windowStart = now)
            } else {
                if (row.windowStart.plus(properties.codeWindow).isBefore(now)) {
                    row.windowStart = now
                    row.sentCount = 0
                }
                if (row.sentCount >= properties.maxCodesPerWindow) {
                    throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many codes requested; try again later")
                }
                row.apply {
                    this.phone = phone
                    codeHash = hash(tenantId, phone, code)
                    this.expiresAt = expiresAt
                    attempts = 0
                    verifiedAt = null
                }
            }
        record.sentCount += 1
        phones.save(record)
        events.publishEvent(PhoneCodeRequested(tenantId = tenantId, phone = phone, code = code))
        return PhoneCodeSent(expiresAt)
    }

    @Transactional(noRollbackFor = [ResponseStatusException::class])
    fun confirmPhone(
        code: String,
        now: Instant = Instant.now(),
    ): PhoneStatusView {
        val tenantId = requireNotNull(TenantContext.get())
        val row =
            phones.findCurrent().firstOrNull()
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Request a code first")
        if (row.verifiedAt != null) return PhoneStatusView(row.phone, verified = true)
        if (row.expiresAt.isBefore(now) || row.attempts >= properties.maxCodeAttempts) {
            throw ResponseStatusException(HttpStatus.GONE, "This code has expired; request a new one")
        }
        if (!MessageDigest.isEqual(hash(tenantId, row.phone, code.trim()).toByteArray(), row.codeHash.toByteArray())) {
            row.attempts += 1
            phones.save(row)
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "This code is not the one we sent")
        }
        row.verifiedAt = now
        phones.save(row)
        return PhoneStatusView(row.phone, verified = true)
    }

    @Transactional
    fun submit(
        submission: VerificationSubmission,
        files: List<UploadedDocument>,
    ): VerificationRequestView {
        val tenantId = requireNotNull(TenantContext.get())
        val legalName = submission.legalName.trim()
        if (legalName.isEmpty() || legalName.length > 200 || submission.documentType.isBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the name on the documents and their type")
        }
        val existing = verifications.findByKindOrderBySubmittedAtDesc(submission.kind)
        if (existing.any { it.status != VerificationStatus.REJECTED }) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "A ${submission.kind.name.lowercase()} verification is already under review or approved",
            )
        }
        val phone =
            if (submission.kind == VerificationKind.PERSONAL) {
                phones
                    .findCurrent()
                    .firstOrNull()
                    ?.takeIf { it.verifiedAt != null }
                    ?.phone
                    ?: throw ResponseStatusException(HttpStatus.CONFLICT, "Confirm your phone number first")
            } else {
                null
            }
        if (submission.kind == VerificationKind.COMPANY && submission.registrationNumber.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the company's registration number (RCCM)")
        }
        if (files.isEmpty() || files.size > properties.maxFiles) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Attach between 1 and ${properties.maxFiles} files")
        }
        val typed =
            files.map { file ->
                if (file.bytes.size > properties.maxFileBytes) {
                    throw ResponseStatusException(
                        HttpStatus.PAYLOAD_TOO_LARGE,
                        "Each file must be at most ${properties.maxFileBytes / 1024 / 1024} MB",
                    )
                }
                file to
                    (
                        documentTypeOf(file.bytes)
                            ?: throw ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Attach JPEG, PNG or PDF files")
                    )
            }

        val request =
            verifications.save(
                OrganizationVerification(
                    kind = submission.kind,
                    legalName = legalName,
                    documentType = submission.documentType.trim().take(40),
                    registrationNumber =
                        submission.registrationNumber
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.take(80),
                    taxIdentifier =
                        submission.taxIdentifier
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.take(80),
                    phone = phone,
                ),
            )
        val id = requireNotNull(request.id)
        val stored = mutableListOf<String>()
        try {
            typed.forEachIndexed { index, (file, type) ->
                val key = "verification/$tenantId/$id/${index + 1}.${type.extension}"
                store.put(key, file.bytes, type.contentType)
                stored += key
                documents.save(
                    VerificationDocument(
                        verificationId = id,
                        position = index + 1,
                        objectKey = key,
                        contentType = type.contentType,
                        sizeBytes = file.bytes.size,
                    ),
                )
            }
        } catch (ex: RuntimeException) {
            // The transaction rolls the rows back; the stored files must not outlive them.
            stored.forEach { runCatching { store.delete(it) } }
            throw ex
        }
        return request.toView()
    }

    private fun hash(
        tenantId: String,
        phone: String,
        code: String,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$tenantId:$phone:$code".toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val CODE_LENGTH = 6
        val PHONE = Regex("^\\+[1-9][0-9]{7,14}$")
    }
}

/** A document type recognized from the file's first bytes. */
enum class DocumentFileType(
    val contentType: String,
    val extension: String,
) {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    PDF("application/pdf", "pdf"),
}

fun documentTypeOf(bytes: ByteArray): DocumentFileType? {
    fun startsWith(vararg signature: Int): Boolean =
        bytes.size >= signature.size && signature.indices.all { bytes[it].toInt() and 0xFF == signature[it] }
    return when {
        startsWith(0xFF, 0xD8, 0xFF) -> DocumentFileType.JPEG
        startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> DocumentFileType.PNG
        startsWith(0x25, 0x50, 0x44, 0x46, 0x2D) -> DocumentFileType.PDF
        else -> null
    }
}

fun OrganizationVerification.toView(): VerificationRequestView =
    VerificationRequestView(
        id = requireNotNull(id),
        kind = kind,
        status = status,
        legalName = legalName,
        documentType = documentType,
        submittedAt = submittedAt,
        decidedAt = decidedAt,
        rejectionReason = rejectionReason,
    )
