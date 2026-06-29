package com.jiku.invitation.internal

import java.util.UUID

data class RowIssue(
    val row: Int,
    val reason: String,
)

data class GuestImportResult(
    val imported: Int,
    val skippedDuplicates: Int,
    val failed: Int,
    val failures: List<RowIssue>,
    val warnings: List<RowIssue>,
)

data class GuestResponse(
    val id: UUID,
    val firstName: String,
    val lastName: String,
    val email: String?,
    val phoneNumber: String?,
)
