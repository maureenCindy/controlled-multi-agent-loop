package com.tenderpulse.auth

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class MagicLinkRequest(
    @field:Email @field:NotBlank val email: String
)

/**
 * Deliberately identical regardless of whether [MagicLinkRequest.email] matched a subscriber —
 * see [AuthService.requestMagicLink]. No boolean/flag field here on purpose: any such field
 * would itself be an enumeration leak.
 */
data class MagicLinkResponse(
    val message: String = "If that email is registered, we've sent a sign-in link."
)

data class VerifyResponse(
    val accessToken: String,
    val tokenType: String = "Bearer"
)
