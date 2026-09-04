package com.tenderpulse

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tenderpulse.domain.SubscriberRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.concurrent.TimeUnit

/**
 * Full-context regression for #84: proves [AsyncExceptionHandlingConfig]'s
 * [LoggingAsyncUncaughtExceptionHandler] is actually wired up as the `@Async` uncaught-exception
 * handler used by the real Spring proxy, not just correct in isolation
 * ([AsyncExceptionHandlingConfigTest]).
 *
 * [com.tenderpulse.auth.AuthService.requestMagicLink] returns `Unit`, so the only way an
 * exception thrown inside it (once dispatched to the background executor by `@Async`) becomes
 * observable at all is via the [org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler]
 * registered for the app. [SubscriberRepository] (a real Spring Data JPA repository bean in every
 * other test) is swapped for a [MockitoBean] here specifically so `findByEmail` can be made to
 * throw -- the one call [AuthService.requestMagicLink] makes that isn't already defensively
 * `runCatching`-wrapped downstream (see [com.tenderpulse.auth.SmtpMagicLinkMailSender], which
 * swallows mail-send failures on purpose and so can't be used to trigger this).
 */
@SpringBootTest
@AutoConfigureMockMvc
class AsyncExceptionHandlingIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockitoBean
    private lateinit var subscriberRepository: SubscriberRepository

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private val handlerLogger = LoggerFactory.getLogger(LoggingAsyncUncaughtExceptionHandler::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun attachAppender() {
        appender.start()
        handlerLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        handlerLogger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `an exception thrown inside requestMagicLink's async execution is captured by the custom handler`() {
        val failure = RuntimeException("simulated DB failure for #84")
        `when`(subscriberRepository.findByEmail(anyString())).thenThrow(failure)

        mockMvc.perform(
            post("/api/v1/auth/magic-link")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"email":"async-failure@example.com"}""")
        ).andExpect(status().isOk) // the endpoint itself never sees the async failure -- see AuthController

        // The failure happens on a background thread after the request already returned (that's
        // the whole point of #70's `@Async`), so poll briefly rather than asserting immediately.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var captured = appender.list.firstOrNull()
        while (captured == null && System.nanoTime() < deadline) {
            Thread.sleep(20)
            captured = appender.list.firstOrNull()
        }

        assertTrue(captured != null, "expected LoggingAsyncUncaughtExceptionHandler to have logged the async failure")
        val event = captured!!
        assertTrue(event.level == Level.ERROR)
        assertTrue(
            event.formattedMessage.contains("requestMagicLink"),
            "expected the failing method name in the log message: ${event.formattedMessage}"
        )
        assertTrue(
            event.formattedMessage.contains("async-failure@example.com"),
            "expected the method's params (the requested email) in the log message: ${event.formattedMessage}"
        )
        val throwableProxy = event.throwableProxy
        assertTrue(throwableProxy != null, "expected the full exception to be attached to the log event")
        assertTrue(throwableProxy!!.className == failure.javaClass.name)
        assertTrue(throwableProxy.message == failure.message)
    }
}
