package com.tenderpulse.auth

/**
 * Thrown by [AuthService.verify] for any token that can't be exchanged for a bearer token.
 * [reason] is a short machine-readable code; [message] (via [RuntimeException]) is the clear,
 * human-readable "link expired, request a new one"-style explanation the issue's AC calls for.
 * [AuthController] maps every subtype to 401.
 */
sealed class InvalidMagicLinkTokenException(val reason: String, message: String) : RuntimeException(message)

class MagicLinkTokenNotFoundException :
    InvalidMagicLinkTokenException("invalid_token", "This link is invalid. Request a new one.")

class MagicLinkTokenExpiredException :
    InvalidMagicLinkTokenException("expired", "This link has expired. Request a new one.")

class MagicLinkTokenAlreadyUsedException :
    InvalidMagicLinkTokenException("already_used", "This link has already been used. Request a new one.")
