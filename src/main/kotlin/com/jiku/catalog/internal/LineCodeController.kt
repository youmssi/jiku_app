package com.jiku.catalog.internal

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Resolves a counter link's short code (JIKU-88) into a fresh signed link pinned
 * to the operator's service — the token [LineStaffController] expects under
 * `/line/{token}`. An operator covering several services opens their console
 * through `/operator-codes/{code}` instead.
 */
@RestController
@RequestMapping("/line-codes")
class LineCodeController(
    private val operators: OperatorService,
) {
    @GetMapping("/{code}")
    fun resolve(
        @PathVariable code: String,
    ): LineCodeResolution = LineCodeResolution(token = operators.resolveCode(code, pinService = true).token)
}

data class LineCodeResolution(
    val token: String,
)
