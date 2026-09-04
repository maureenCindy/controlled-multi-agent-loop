package com.tenderpulse.api

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * Global exception handling (#64): before this, a database-level uniqueness violation had
 * nowhere to be caught anywhere in the app, so it surfaced as a raw, unhandled 500 with a stack
 * trace instead of a clean 409.
 *
 * The concrete gap: both [com.tenderpulse.subscriber.SubscriberService.register] (email
 * uniqueness) and [com.tenderpulse.subscriber.SubscriberService.registerPro] (PayPal
 * `paypalSubscriptionId` uniqueness) have an app-level "does this already exist?" check *before*
 * the save — but that check-then-save has a TOCTOU race: two concurrent requests for the same
 * email/subscription ID can both pass the check before either commits. The DB's `unique = true`
 * constraint (see [com.tenderpulse.domain.Subscriber]) is what actually stops the losing request
 * from being persisted; this handler only shapes *its* response. No retry logic or optimistic
 * locking is introduced here — that's explicitly out of scope for #64, which is purely about a
 * graceful error response, not concurrency control.
 *
 * `DataIntegrityViolationException` is Spring's translated form of the underlying
 * persistence-provider failure (e.g. Hibernate's `ConstraintViolationException` wrapping a JDBC
 * unique-constraint error) — Spring Data JPA repositories already have exception translation
 * enabled via `@Repository`, so this is the right (and sufficiently general) type to catch here:
 * it covers every current and future unique constraint on any entity, not just
 * [com.tenderpulse.domain.Subscriber]'s two.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleDataIntegrityViolation(ex: DataIntegrityViolationException): ResponseEntity<Map<String, String>> {
        // Full exception (with stack trace) goes to the server log for diagnosis; the client only
        // ever sees the structured, stack-trace-free body below.
        log.warn("Data integrity violation mapped to 409: {}", ex.message)
        return ResponseEntity.status(HttpStatus.CONFLICT).body(
            mapOf(
                "error" to "conflict",
                "message" to "The request could not be completed because it conflicts with an existing record."
            )
        )
    }
}
