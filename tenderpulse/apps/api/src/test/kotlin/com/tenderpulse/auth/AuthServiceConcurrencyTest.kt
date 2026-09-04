package com.tenderpulse.auth

import com.tenderpulse.domain.SubscriberRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for #70's TOCTOU race in [AuthService.verify]'s single-use check: a plain
 * find-then-check-then-save leaves a window where two near-simultaneous requests with the same
 * token can both observe `usedAt == null` before either writes. This races two real threads
 * against a real (H2) [MagicLinkTokenRepository] — not a mock — so it's the atomic conditional
 * `UPDATE ... WHERE used_at IS NULL` (see [MagicLinkTokenRepository.markUsed]) that decides the
 * winner, exactly as it would against the real Postgres datasource in production.
 *
 * `@DataJpaTest` gives a real, transactional [MagicLinkTokenRepository]; [AuthService] is built
 * directly (not autowired) with mocked [SubscriberRepository]/[MagicLinkMailSender]/
 * [BearerTokenService] — same wiring style as
 * [com.tenderpulse.subscriber.SubscriberServiceConcurrencyTest] (#64) — since none of those three
 * collaborators are what this test is about.
 *
 * Because thread scheduling can't be controlled precisely, either racing call may end up as the
 * "fast" already-used rejection (finds `usedAt` already set) or the "slow" one (finds it null,
 * then loses the atomic claim) — both are the same clean, existing
 * [MagicLinkTokenAlreadyUsedException], so this asserts on what must hold regardless of
 * scheduling: exactly one success, exactly one clean failure, and the token ends up marked used
 * exactly once.
 *
 * `@Transactional(propagation = NOT_SUPPORTED)` overrides `@DataJpaTest`'s default of wrapping
 * the whole test method in one rolled-back transaction pinned to the test thread's connection —
 * with that default, the setup `save()` below would never be visible to the two racing
 * background threads (different connections) at all, since it wouldn't be committed until after
 * (and only if) the test method itself committed. Suspending it means every repository call —
 * setup included — runs (and commits) in its own transaction, visible across threads/connections
 * exactly as it would against the real database in production.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AuthServiceConcurrencyTest {

    @Autowired
    private lateinit var tokenRepository: MagicLinkTokenRepository

    private val subscriberRepository = mockk<SubscriberRepository>()
    private val mailSender = mockk<MagicLinkMailSender>(relaxed = true)
    private val bearerTokenService = mockk<BearerTokenService>()

    @Test
    fun `two concurrent verify calls with the same valid token - exactly one succeeds, the other fails cleanly`() {
        val subscriberId = UUID.randomUUID()
        val rawToken = "race-condition-raw-token"
        val stored = tokenRepository.save(
            MagicLinkToken(
                subscriberId = subscriberId,
                tokenHash = TokenHasher.hash(rawToken),
                expiresAt = Instant.now().plusSeconds(3600)
            )
        )
        every { bearerTokenService.issue(subscriberId, any()) } returns "issued-bearer-token"

        val service = AuthService(
            subscriberRepository = subscriberRepository,
            tokenRepository = tokenRepository,
            mailSender = mailSender,
            bearerTokenService = bearerTokenService,
            magicLinkTtlMs = 86_400_000,
            verifyBaseUrl = "https://api.tenderpulse.example/api/v1/auth/verify"
        )

        val threadCount = 2
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)
        val cleanFailures = AtomicInteger(0)
        val unexpectedFailures = mutableListOf<Throwable>()
        val executor = Executors.newFixedThreadPool(threadCount)

        val futures = (1..threadCount).map {
            executor.submit {
                ready.countDown()
                start.await()
                try {
                    service.verify(rawToken)
                    successes.incrementAndGet()
                } catch (ex: MagicLinkTokenAlreadyUsedException) {
                    cleanFailures.incrementAndGet()
                } catch (ex: Throwable) {
                    synchronized(unexpectedFailures) { unexpectedFailures += ex }
                }
            }
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS), "threads never reached the start line")
        start.countDown()
        futures.forEach { it.get(15, TimeUnit.SECONDS) }
        executor.shutdown()

        assertTrue(unexpectedFailures.isEmpty()) { "Unexpected (non-clean) failures: $unexpectedFailures" }
        assertEquals(1, successes.get(), "exactly one of the two racing verify() calls should succeed")
        assertEquals(
            1,
            cleanFailures.get(),
            "the other must fail cleanly with the existing MagicLinkTokenAlreadyUsedException, not crash"
        )

        val persisted = tokenRepository.findById(stored.id).orElseThrow()
        assertNotNull(persisted.usedAt, "the token must end up marked used after the race resolves")
    }
}
