package com.tenderpulse.notification

import com.tenderpulse.auth.UnsubscribeToken
import com.tenderpulse.auth.UnsubscribeTokenRepository
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.NotificationRecordRepository
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * Full-context regression for issue #96: a DB-level failure inside
 * [com.tenderpulse.auth.UnsubscribeService.buildUnsubscribeLink] for one subscriber must not roll
 * back other subscribers' already-completed work in the same
 * [NotificationService.notifyMatchingSubscribers] batch.
 *
 * `@SpringBootTest` (not `@DataJpaTest`) so the *real* `@Transactional` AOP proxies for both
 * [NotificationService.notifyMatchingSubscribers] (the outer, whole-batch transaction) and
 * [com.tenderpulse.auth.UnsubscribeService.buildUnsubscribeLink] (now
 * `Propagation.REQUIRES_NEW`) are exercised against a real transaction manager and a real
 * (H2) database — the actual propagation semantics that caused #96 cannot be observed against
 * mocked repositories/services, only against real Spring-managed transactions. Only
 * [UnsubscribeTokenRepository] is a [MockitoBean], stubbed to throw for exactly one subscriber
 * mid-batch to deterministically force the "DB-level failure" #96 describes, exactly as the issue
 * suggests; [SubscriberRepository], [InterestProfileRepository], [TenderRepository] and
 * [NotificationRecordRepository] are all real beans, so what actually got committed to the (real,
 * H2) database is asserted by reading it back afterwards, not by verifying mock interactions.
 * [JavaMailSender] is mocked per the "no live network" test principle used throughout this suite
 * (see [com.tenderpulse.api.UnsubscribeIntegrationTest]).
 */
@SpringBootTest
class UnsubscribeLinkTransactionIsolationTest {

    @Autowired
    private lateinit var notificationService: NotificationService

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var interestProfileRepository: InterestProfileRepository

    @Autowired
    private lateinit var tenderRepository: TenderRepository

    @Autowired
    private lateinit var notificationRecordRepository: NotificationRecordRepository

    @MockitoBean
    private lateinit var tokenRepository: UnsubscribeTokenRepository

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private fun paidSubscriber(email: String): Subscriber =
        subscriberRepository.save(Subscriber(email = email, tier = SubscriptionTier.PAID))

    /**
     * The (real, H2) DB and Spring context are shared across the whole test suite
     * (`DB_CLOSE_DELAY=-1`, cached `@SpringBootTest` context — see `application.yml`), so a
     * sector-less/keyword-less "matches everything" profile here would also match every other
     * test class's tenders and vice versa, corrupting the `sent` counts asserted below. Scoping
     * both the profile and the tender to a random per-test [issuingAuthorityContains] marker
     * (same technique other full-context tests use with a specific [Sector] instead) makes each
     * test's subscribers match only that test's own tender.
     */
    private fun matchingProfile(subscriber: Subscriber, marker: String): InterestProfile =
        interestProfileRepository.save(
            InterestProfile(subscriber = subscriber, active = true, issuingAuthorityContains = marker)
        )

    private fun tender(sourceUrl: String, marker: String): Tender = tenderRepository.save(
        Tender(
            title = "Supply of laboratory equipment",
            issuingAuthority = "Ministry of Health ($marker)",
            sourceUrl = sourceUrl,
            sourceName = "egp.praz.org.zw"
        )
    )

    /**
     * #96 test case 1: [UnsubscribeTokenRepository.save] fails with a real DB-style unchecked
     * exception for subscriber A, mid-batch. Subscriber A's own notification fails gracefully
     * (existing [EmailNotificationSender] behavior, unchanged by this fix), but subscriber B —
     * processed in the same [NotificationService.notifyMatchingSubscribers] call/transaction —
     * must still succeed and, crucially, must still be **committed**: before this fix, A's
     * failure would silently mark the whole batch's transaction rollback-only, so the call would
     * either throw `UnexpectedRollbackException` or (if swallowed higher up) discard B's
     * already-"successful" work without a trace, which is exactly the risk #96 flags.
     */
    @Test
    fun `a DB-level failure minting subscriber A's unsubscribe link does not roll back subscriber B's already-completed work`() {
        val marker = "TP-96-${UUID.randomUUID()}"
        val subscriberA = paidSubscriber("subscriber-a-db-failure@example.co.zw")
        val subscriberB = paidSubscriber("subscriber-b-still-commits@example.co.zw")
        matchingProfile(subscriberA, marker)
        matchingProfile(subscriberB, marker)
        val tender = tender("https://egp.praz.org.zw/tenders/2026/TR-96-001", marker)

        `when`(tokenRepository.save(argThat { token: UnsubscribeToken? -> token?.subscriberId == subscriberA.id }))
            .thenThrow(DataIntegrityViolationException("simulated DB failure for #96"))
        `when`(tokenRepository.save(argThat { token: UnsubscribeToken? -> token?.subscriberId == subscriberB.id }))
            .thenAnswer { it.arguments[0] }

        // Must not throw (in particular, must not throw UnexpectedRollbackException) — the whole
        // point of #96 is that one subscriber's DB failure must not blow up the batch call itself.
        val sent = notificationService.notifyMatchingSubscribers(tender)

        assertEquals(1, sent, "only subscriber B's send should count as sent")

        val records = notificationRecordRepository.findAll()
        val recordA = records.singleOrNull { it.subscriber.id == subscriberA.id }
        val recordB = records.singleOrNull { it.subscriber.id == subscriberB.id }

        assertTrue(recordA != null, "subscriber A's failed-send NotificationRecord must still be committed")
        assertFalse(recordA!!.success, "subscriber A's send must be recorded as a failure")

        assertTrue(recordB != null, "subscriber B's successful-send NotificationRecord must be committed, not rolled back")
        assertTrue(recordB!!.success, "subscriber B's send must be recorded as a success")
    }

    /** #96 test case 2: a normal batch with no failures behaves exactly as before this fix. */
    @Test
    fun `a normal batch with no unsubscribe-link failures is unaffected by the propagation change`() {
        val marker = "TP-96-${UUID.randomUUID()}"
        val subscriberA = paidSubscriber("subscriber-a-no-failure@example.co.zw")
        val subscriberB = paidSubscriber("subscriber-b-no-failure@example.co.zw")
        matchingProfile(subscriberA, marker)
        matchingProfile(subscriberB, marker)
        val tender = tender("https://egp.praz.org.zw/tenders/2026/TR-96-002", marker)

        `when`(tokenRepository.save(argThat { token: UnsubscribeToken? -> true }))
            .thenAnswer { it.arguments[0] }

        val sent = notificationService.notifyMatchingSubscribers(tender)

        assertEquals(2, sent, "both subscribers should succeed when nothing fails")
        val records = notificationRecordRepository.findAll()
        assertTrue(records.any { it.subscriber.id == subscriberA.id && it.success })
        assertTrue(records.any { it.subscriber.id == subscriberB.id && it.success })
    }
}
