package com.tenderpulse

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/**
 * Unit test for [LoggingAsyncUncaughtExceptionHandler] (#84), independent of a Spring context:
 * calls [LoggingAsyncUncaughtExceptionHandler.handleUncaughtException] directly with a synthetic
 * method/exception and asserts on the resulting log event, which is the same mechanism Spring's
 * async proxy uses when an `@Async` method throws (see [AsyncExceptionHandlingConfig]'s kdoc).
 * [AsyncExceptionHandlingIntegrationTest] complements this with a full-context test proving the
 * handler is actually wired up and invoked for a real `@Async` failure, not just correct in
 * isolation.
 */
class AsyncExceptionHandlingConfigTest {

    private val logger = LoggerFactory.getLogger(LoggingAsyncUncaughtExceptionHandler::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `handleUncaughtException logs method name, params and the full exception at ERROR`() {
        val handler = LoggingAsyncUncaughtExceptionHandler()
        val method = AuthServiceStandIn::class.java.getDeclaredMethod("requestMagicLink", String::class.java)
        val exception = IllegalStateException("simulated async failure")

        handler.handleUncaughtException(exception, method, "someone@example.com")

        assertEquals(1, appender.list.size, "expected exactly one log event")
        val event = appender.list.single()

        assertEquals(Level.ERROR, event.level)
        val formattedMessage = event.formattedMessage
        assertTrue(formattedMessage.contains("requestMagicLink"), "expected method name in log message: $formattedMessage")
        assertTrue(formattedMessage.contains("someone@example.com"), "expected params in log message: $formattedMessage")
        assertTrue(
            formattedMessage.contains(AuthServiceStandIn::class.java.name),
            "expected declaring class in log message: $formattedMessage"
        )

        // The full exception (with stack trace) must be attached to the log event, not just its
        // message folded into the text above -- matching #84's "full exception" acceptance
        // criterion. `throwableProxy` (rather than a substring of the formatted message) is what
        // actually drives Logback rendering a stack trace in real log output.
        val throwableProxy = event.throwableProxy
        assertTrue(throwableProxy != null, "expected the exception to be attached to the log event")
        assertEquals(exception.javaClass.name, throwableProxy!!.className)
        assertEquals(exception.message, throwableProxy.message)
        assertTrue(throwableProxy.stackTraceElementProxyArray.isNotEmpty(), "expected a non-empty stack trace")
    }

    /** Stand-in class purely so a real [java.lang.reflect.Method] can be reflected off it above. */
    private class AuthServiceStandIn {
        @Suppress("unused")
        fun requestMagicLink(email: String) {}
    }
}
