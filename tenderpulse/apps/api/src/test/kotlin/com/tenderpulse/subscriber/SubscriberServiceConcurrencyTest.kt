package com.tenderpulse.subscriber

import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.paypal.PayPalClient
import com.tenderpulse.paypal.PayPalSubscriberInfo
import com.tenderpulse.paypal.PayPalSubscriptionResponse
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.dao.DataIntegrityViolationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression coverage for #64: [SubscriberService.registerPro]'s app-level "is this PayPal
 * subscription id already linked?" check has a TOCTOU race — two concurrent requests for the
 * *same* subscription id can both pass `findByPaypalSubscriptionId` (both see "not linked yet")
 * before either commits its `save`. This races two real threads against a real (H2) repository
 * — not a mock — so it's the actual DB unique constraint on `Subscriber.paypalSubscriptionId`
 * that decides the winner, exactly as it would in production.
 *
 * The two racing requests deliberately use **two different emails** for the same subscription
 * id (not the same email twice): [Subscriber.email] is *also* unique, so racing the same email
 * would let the insert collide on the email index instead of (or as well as) the
 * `paypalSubscriptionId` one — H2 reports only one violated constraint per failed statement, and
 * in practice it consistently reports `email` first when both would be violated, which would
 * make this test pass for the wrong reason (email racing, already covered by
 * [com.tenderpulse.api.SubscriberControllerTest] and
 * [com.tenderpulse.domain.SubscriberUniquenessConstraintTest]) without ever actually exercising
 * the `paypalSubscriptionId` constraint this test is named for. [payPalClient] is stubbed with an
 * `answers` block keyed off a `ThreadLocal` so each thread's mocked PayPal response reports back
 * *that thread's own* request email as the payer email — satisfying `registerPro`'s "payer email
 * must match the request" check for both threads independently — while every other field
 * (crucially `paypalSubscriptionId`) stays identical across both requests, isolating the race to
 * that one column.
 *
 * `@DataJpaTest` gives a real, transactional [SubscriberRepository]; [SubscriberService] is
 * built directly (not autowired) — same wiring style as [SubscriberServiceTest] — since
 * verifying-with-PayPal is not what this test is about.
 *
 * Because the two request emails never collide, the DB has only one axis left to race on
 * (`paypalSubscriptionId`), so the losing thread is expected to fail with exactly
 * [DataIntegrityViolationException] — not [com.tenderpulse.domain.SubscriptionVerificationException]
 * (the sequential, non-racy "already linked" rejection), which would only occur if one thread's
 * entire call (check *and* commit) finished before the other's check even ran. Verified locally
 * across 20 consecutive runs (`--rerun`, 5 at a time) with zero deviation from that outcome
 * before asserting it here specifically, per #64 review feedback that a looser
 * "either exception is fine" assertion had let a wrong-constraint test bug go unnoticed.
 */
@DataJpaTest
class SubscriberServiceConcurrencyTest {

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    private val profileRepository = mockk<InterestProfileRepository>()
    private val payPalClient = mockk<PayPalClient>()
    private val expectedPlanId = "P-EXPECTED-PLAN"

    @Test
    fun `two concurrent registerPro calls for the same subscription id from different emails - the loser fails with DataIntegrityViolationException`() {
        val subscriptionId = "I-RACE-CONDITION-1"
        val emailA = "race-a@example.com"
        val emailB = "race-b@example.com"

        // Each racing thread sets its own expected payer email just before calling registerPro(),
        // so the mocked PayPal response always reports back the *calling* thread's own request
        // email — both requests pass the payer-email check on their own merits, with
        // paypalSubscriptionId as the only column shared between the two rows being inserted.
        val expectedPayerEmail = ThreadLocal<String>()
        every { payPalClient.fetchSubscription(subscriptionId) } answers {
            PayPalSubscriptionResponse(
                id = subscriptionId,
                status = "ACTIVE",
                planId = expectedPlanId,
                subscriber = PayPalSubscriberInfo(emailAddress = expectedPayerEmail.get())
            )
        }

        val service = SubscriberService(subscriberRepository, profileRepository, payPalClient, expectedPlanId)
        val requests = listOf(
            ProSubscribeRequest(email = emailA, paypalSubscriptionId = subscriptionId),
            ProSubscribeRequest(email = emailB, paypalSubscriptionId = subscriptionId)
        )

        val threadCount = requests.size
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)
        val conflicts = AtomicInteger(0)
        val unexpectedFailures = mutableListOf<Throwable>()
        val executor = Executors.newFixedThreadPool(threadCount)

        val futures = requests.map { req ->
            executor.submit {
                expectedPayerEmail.set(req.email)
                ready.countDown()
                start.await()
                try {
                    service.registerPro(req)
                    successes.incrementAndGet()
                } catch (ex: DataIntegrityViolationException) {
                    conflicts.incrementAndGet()
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
        assertEquals(1, successes.get(), "exactly one of the two racing requests should succeed")
        assertEquals(
            1,
            conflicts.get(),
            "the other request must fail with DataIntegrityViolationException (the paypalSubscriptionId " +
                "unique constraint firing), not any other exception, and not silently succeed"
        )
        assertEquals(
            1,
            subscriberRepository.findAll().count { it.paypalSubscriptionId == subscriptionId },
            "only one Subscriber row should ever be persisted for this subscription id"
        )
    }
}
