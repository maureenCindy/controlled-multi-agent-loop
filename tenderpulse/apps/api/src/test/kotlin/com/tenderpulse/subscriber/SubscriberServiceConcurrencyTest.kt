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
 * `@DataJpaTest` gives a real, transactional [SubscriberRepository]; [SubscriberService] is
 * built directly (not autowired) with a mocked [PayPalClient] — same wiring style as
 * [SubscriberServiceTest] — since verifying-with-PayPal is not what this test is about.
 *
 * Because thread scheduling can't be controlled precisely, this accepts either clean outcome for
 * the losing thread — [DataIntegrityViolationException] (the DB constraint firing, i.e. the true
 * race this issue is about — [com.tenderpulse.api.GlobalExceptionHandler] maps this to 409) or
 * [com.tenderpulse.domain.SubscriptionVerificationException] (the app-level check winning
 * outright, if the first thread's transaction happens to fully commit before the second thread's
 * check runs) — and asserts on what must hold regardless of scheduling: exactly one success,
 * exactly one (clean, non-crashing) failure, and exactly one persisted row for that subscription
 * id.
 */
@DataJpaTest
class SubscriberServiceConcurrencyTest {

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    private val profileRepository = mockk<InterestProfileRepository>()
    private val payPalClient = mockk<PayPalClient>()
    private val expectedPlanId = "P-EXPECTED-PLAN"

    @Test
    fun `two concurrent registerPro calls for the same subscription id - exactly one succeeds, the other fails cleanly`() {
        val subscriptionId = "I-RACE-CONDITION-1"
        val email = "race@example.com"
        every { payPalClient.fetchSubscription(subscriptionId) } returns PayPalSubscriptionResponse(
            id = subscriptionId,
            status = "ACTIVE",
            planId = expectedPlanId,
            subscriber = PayPalSubscriberInfo(emailAddress = email)
        )

        val service = SubscriberService(subscriberRepository, profileRepository, payPalClient, expectedPlanId)
        val req = ProSubscribeRequest(email = email, paypalSubscriptionId = subscriptionId)

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
                    service.registerPro(req)
                    successes.incrementAndGet()
                } catch (ex: DataIntegrityViolationException) {
                    cleanFailures.incrementAndGet()
                } catch (ex: com.tenderpulse.domain.SubscriptionVerificationException) {
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
        assertEquals(1, successes.get(), "exactly one of the two racing requests should succeed")
        assertEquals(1, cleanFailures.get(), "the other request must fail cleanly (409-mappable), not crash")
        assertEquals(
            1,
            subscriberRepository.findAll().count { it.paypalSubscriptionId == subscriptionId },
            "only one Subscriber row should ever be persisted for this subscription id"
        )
    }
}
