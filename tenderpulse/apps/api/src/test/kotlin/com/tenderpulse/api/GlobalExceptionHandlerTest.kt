package com.tenderpulse.api

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus

/**
 * Direct unit test of [GlobalExceptionHandler] (#64): the exception-to-409 mapping itself,
 * independent of how [org.springframework.dao.DataIntegrityViolationException] ends up being
 * thrown (see [com.tenderpulse.api.SubscriberControllerTest] for the controller-level wiring,
 * and [com.tenderpulse.subscriber.SubscriberServiceConcurrencyTest] for a real race against the
 * database that actually produces one).
 */
class GlobalExceptionHandlerTest {

    private val handler = GlobalExceptionHandler()

    @Test
    fun `maps DataIntegrityViolationException to 409 with a structured, non-stack-trace body`() {
        val ex = DataIntegrityViolationException(
            "could not execute statement; SQL [n/a]; constraint [uk_subscribers_email]"
        )

        val response = handler.handleDataIntegrityViolation(ex)

        assertEquals(HttpStatus.CONFLICT, response.statusCode)
        val body = requireNotNull(response.body)
        assertEquals("conflict", body["error"])
        assertFalse(body["message"].isNullOrBlank())
        // The response body must not leak the raw exception message / stack trace.
        assertFalse(body.values.any { it.contains("uk_subscribers_email") })
    }
}
