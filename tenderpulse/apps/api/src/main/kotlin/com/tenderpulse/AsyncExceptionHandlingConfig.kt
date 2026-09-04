package com.tenderpulse

import org.slf4j.LoggerFactory
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.AsyncConfigurer
import java.lang.reflect.Method
import java.util.concurrent.Executor

/**
 * Registers a custom [AsyncUncaughtExceptionHandler] for `@Async` methods (#84, following up on
 * TP-070/#70).
 *
 * TP-070 made [com.tenderpulse.auth.AuthService.requestMagicLink] `@Async` (to close a timing
 * side-channel — see that method's kdoc). Because it returns `Unit`, Spring's async proxy has no
 * `Future`/`CompletableFuture` result for the caller to inspect, so any exception thrown inside it
 * (a DB failure, an SMTP failure, ...) can never propagate back to
 * [com.tenderpulse.api.AuthController]. Without an [AsyncConfigurer] bean like this one, such an
 * exception is only caught by Spring's built-in default handler
 * ([org.springframework.aop.interceptor.SimpleAsyncUncaughtExceptionHandler]), which logs a single
 * generic line and nothing else — an operator has no way to see *which* request failed or why. A
 * unique [AsyncConfigurer] bean is auto-detected by `@EnableAsync`
 * ([com.tenderpulse.TenderPulseApplication]) and swaps in [getAsyncUncaughtExceptionHandler] in
 * place of that default.
 *
 * [getAsyncExecutor] deliberately returns `null` rather than constructing/wiring a new executor:
 * per [AsyncConfigurer]'s contract, a `null` return defers to Spring's normal default-executor
 * resolution, which finds and reuses the single [Executor] bean already in the context — Spring
 * Boot's auto-configured `applicationTaskExecutor` (see
 * [com.tenderpulse.TenderPulseApplication]'s kdoc). Implementing [AsyncConfigurer] here is
 * otherwise unrelated to executor selection; it is only the vehicle Spring provides for
 * registering a custom [AsyncUncaughtExceptionHandler], and returning a real executor here would
 * risk silently replacing that shared thread pool with a second, unpooled one.
 */
@Configuration
class AsyncExceptionHandlingConfig : AsyncConfigurer {

    override fun getAsyncExecutor(): Executor? = null

    override fun getAsyncUncaughtExceptionHandler(): AsyncUncaughtExceptionHandler =
        LoggingAsyncUncaughtExceptionHandler()
}

/**
 * Logs enough structured detail about an uncaught exception from an `@Async` method to actually
 * diagnose it after the fact: which method, what it was called with, and the full exception
 * (message + stack trace) — see [AsyncExceptionHandlingConfig]'s kdoc for why this is needed at
 * all. Deliberately a plain top-level class (not an anonymous/inner class) so it can be
 * instantiated and asserted against directly in unit tests, independent of a full Spring context.
 */
class LoggingAsyncUncaughtExceptionHandler : AsyncUncaughtExceptionHandler {

    private val log = LoggerFactory.getLogger(LoggingAsyncUncaughtExceptionHandler::class.java)

    override fun handleUncaughtException(ex: Throwable, method: Method, vararg params: Any?) {
        // The trailing `ex` argument, beyond the four `{}` placeholders below, is SLF4J's
        // documented convention for "log the full exception, including its stack trace" rather
        // than substituting it into the message text — see e.g. GlobalExceptionHandler for the
        // same pattern used elsewhere in this codebase.
        log.error(
            "Uncaught exception in @Async method {}#{}({}) invoked with params {}",
            method.declaringClass.name,
            method.name,
            method.parameterTypes.joinToString(", ") { it.simpleName },
            params.toList(),
            ex
        )
    }
}
