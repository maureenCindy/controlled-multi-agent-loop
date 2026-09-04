package com.tenderpulse.api

import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Regression coverage for #70's timing side-channel: before this fix, `POST /api/v1/auth/magic-link`
 * did a DB write + synchronous SMTP send only for a matched email, vs. a single SELECT for an
 * unmatched one — a latency gap an external observer could use to enumerate registered emails
 * without ever seeing a different response body (the two response bodies were, and remain,
 * identical — see [AuthControllerTest] and [AuthIntegrationTest]).
 *
 * [com.tenderpulse.auth.AuthService.requestMagicLink] is now `@Async` (`@EnableAsync` on
 * [com.tenderpulse.TenderPulseApplication]), so the request thread returns as soon as the call is
 * *submitted* to the background executor, before either branch's actual work (a SELECT, or a
 * SELECT + INSERT + SMTP send) runs. This test measures real wall-clock response latency for a
 * batch of matched vs. unmatched requests — rather than just reasoning about the fix — and
 * asserts the two distributions aren't meaningfully distinguishable.
 *
 * A real `@SpringBootTest` context is required, not the standalone-MockMvc [AuthControllerTest]
 * or the mockk-based [com.tenderpulse.auth.AuthServiceTest] — `@Async` only takes effect through
 * Spring's real proxy, so this is the only place that can actually observe the timing behaviour
 * described above. [JavaMailSender] is mocked so no live network is attempted, matching
 * [AuthIntegrationTest]'s approach.
 */
@SpringBootTest
@AutoConfigureMockMvc
class MagicLinkTimingTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private val log = LoggerFactory.getLogger(javaClass)

    @Test
    fun `magic-link response timing for a matched email is not meaningfully slower than for an unmatched one`() {
        val matchedEmail = "timing-matched@example.com"
        subscriberRepository.save(Subscriber(email = matchedEmail))

        val samples = 40
        val warmupRounds = 5

        fun sampleOnceNanos(email: String): Long {
            val start = System.nanoTime()
            mockMvc.perform(
                post("/api/v1/auth/magic-link")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"email":"$email"}""")
            ).andExpect(status().isOk)
            return System.nanoTime() - start
        }

        // Warm up (JIT, connection/thread-pool setup) before measuring, alternating both paths
        // so neither gets an unfair warmup advantage.
        repeat(warmupRounds) { i ->
            sampleOnceNanos(matchedEmail)
            sampleOnceNanos("timing-unmatched-warmup-$i@example.com")
        }

        val matchedNanos = (1..samples).map { sampleOnceNanos(matchedEmail) }
        val unmatchedNanos = (1..samples).map { sampleOnceNanos("timing-unmatched-$it@example.com") }

        fun meanMs(values: List<Long>) = values.map { it / 1_000_000.0 }.average()
        fun stdDevMs(values: List<Long>): Double {
            val valuesMs = values.map { it / 1_000_000.0 }
            val mean = valuesMs.average()
            return sqrt(valuesMs.sumOf { (it - mean) * (it - mean) } / valuesMs.size)
        }

        val matchedMeanMs = meanMs(matchedNanos)
        val unmatchedMeanMs = meanMs(unmatchedNanos)
        val matchedStdDevMs = stdDevMs(matchedNanos)
        val unmatchedStdDevMs = stdDevMs(unmatchedNanos)
        val actualDeltaMs = abs(matchedMeanMs - unmatchedMeanMs)

        log.info(
            "magic-link timing over {} samples each (after {} warmup rounds) — " +
                "matched mean {}ms (sd {}ms), unmatched mean {}ms (sd {}ms), delta {}ms",
            samples,
            warmupRounds,
            "%.3f".format(matchedMeanMs),
            "%.3f".format(matchedStdDevMs),
            "%.3f".format(unmatchedMeanMs),
            "%.3f".format(unmatchedStdDevMs),
            "%.3f".format(actualDeltaMs)
        )

        // Generous, CI-jitter-tolerant threshold: the historical gap this closes was a
        // synchronous DB write + SMTP send (tens of milliseconds), not sub-millisecond noise, so
        // a wide margin (50ms, or 5x the larger of the two observed standard deviations,
        // whichever is bigger) is still a meaningful assertion, not a rubber stamp.
        val allowedDeltaMs = maxOf(50.0, 5 * maxOf(matchedStdDevMs, unmatchedStdDevMs))

        assertTrue(actualDeltaMs < allowedDeltaMs) {
            "matched vs unmatched mean response time differs by ${"%.3f".format(actualDeltaMs)}ms, " +
                "exceeding the ${"%.3f".format(allowedDeltaMs)}ms tolerance — possible timing " +
                "side-channel regression (matched mean ${"%.3f".format(matchedMeanMs)}ms, " +
                "unmatched mean ${"%.3f".format(unmatchedMeanMs)}ms)"
        }
    }
}
