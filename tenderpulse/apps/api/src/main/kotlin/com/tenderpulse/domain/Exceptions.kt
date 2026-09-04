package com.tenderpulse.domain

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Shared domain-level exceptions. Live in `domain` (rather than `api`) so service classes
 * (e.g. [com.tenderpulse.subscriber.SubscriberService]) can throw them without depending on
 * the `api` package that owns controllers/DTOs — controllers depend on services, not the
 * other way around.
 *
 * `@ResponseStatus` lets Spring's default exception resolver translate these into the right
 * HTTP status regardless of which layer throws them.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
class NotFoundException(message: String) : RuntimeException(message)

@ResponseStatus(HttpStatus.CONFLICT)
class ConflictException(message: String) : RuntimeException(message)

/**
 * A client-supplied PayPal subscription ID could not be verified as an active, matching-plan
 * subscription (TP-042) — covers "doesn't exist", "wrong plan", and "not ACTIVE" alike, since in
 * all three cases the caller's claim is simply rejected, not trusted. Maps to 400 rather than 404
 * because the *request* (email + subscription ID pair) is what's invalid, not a server-side
 * resource lookup.
 */
@ResponseStatus(HttpStatus.BAD_REQUEST)
class SubscriptionVerificationException(message: String) : RuntimeException(message)

/**
 * The call to PayPal's API itself failed (network error, timeout, 5xx from PayPal) — distinct
 * from [SubscriptionVerificationException], which means PayPal answered but the subscription
 * didn't check out. Maps to 502 (this service acting as a gateway to PayPal, which failed).
 */
@ResponseStatus(HttpStatus.BAD_GATEWAY)
class PayPalApiException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
