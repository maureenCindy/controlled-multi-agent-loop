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
