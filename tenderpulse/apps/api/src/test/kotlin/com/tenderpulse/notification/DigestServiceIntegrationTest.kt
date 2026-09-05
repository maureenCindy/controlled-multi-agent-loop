package com.tenderpulse.notification

import com.tenderpulse.domain.DigestQueueEntry
import com.tenderpulse.domain.DigestQueueEntryRepository
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/**
 * Issue #97: full end-to-end regression for [DigestService.runDigestCycle] against real,
 * JPA-backed repositories (H2) — only [JavaMailSender] is mocked, exactly the boundary
 * [UnsubscribeLinkTransactionIsolationTest] and [com.tenderpulse.notification.DigestMailSenderTest]
 * already mock at, so [DigestMailSender]/`SmtpDigestMailSender` itself is a real bean building
 * real email content from real, freshly-loaded entities.
 *
 * TP-013's review (issue #92, https://github.com/maureenCindy/controlled-multi-agent-loop/pull/95)
 * raised a plausible lazy-loading risk: [DigestQueueEntryRepository.findAllByDigestedAtIsNull] has
 * no `JOIN FETCH` (unlike [com.tenderpulse.domain.InterestProfileRepository.findAllActiveWithSubscriber]),
 * and [DigestService.runDigestCycle] is deliberately not `@Transactional` as a whole, so each
 * [DigestQueueEntry.subscriber]/[DigestQueueEntry.tender]/[DigestQueueEntry.profile] association
 * (all `FetchType.LAZY`) is accessed to build the outbound email body after the query's own
 * short-lived transaction has already closed. A Reviewer spike found this doesn't reproduce today,
 * but nothing in the committed suite would catch a future regression here — this test is that
 * regression guard: it asserts on the *actual* digest email content, which would fail (either with
 * a thrown `LazyInitializationException` or with blank/garbage proxy content) if that ever changed.
 *
 * `@SpringBootTest` (not `@DataJpaTest`) is required, not incidental: only a real Spring-managed
 * transaction boundary around each repository call (as opposed to one enclosing test-method
 * transaction, which `@DataJpaTest`/`@Transactional` tests use and which would keep the Hibernate
 * session open for the whole test, masking exactly the risk being tested for) reproduces the real
 * runtime shape of a scheduled-job invocation.
 *
 * The (real, H2) DB and Spring context are shared across the whole test suite (`DB_CLOSE_DELAY=-1`,
 * cached `@SpringBootTest` context — same caveat as [UnsubscribeLinkTransactionIsolationTest]), so
 * other test classes' leftover, still-undigested [DigestQueueEntry] rows (e.g.
 * `com.tenderpulse.domain.EntityPersistenceTest`) may also be picked up and digested by a
 * `runDigestCycle()` call made here. Every assertion below is therefore scoped to this test's own,
 * uniquely-marked subscriber email addresses rather than to the total counts/invocations on the
 * shared [javaMailSender] mock.
 */
@SpringBootTest
class DigestServiceIntegrationTest {

    @Autowired
    private lateinit var digestService: DigestService

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var tenderRepository: TenderRepository

    @Autowired
    private lateinit var interestProfileRepository: InterestProfileRepository

    @Autowired
    private lateinit var digestQueueEntryRepository: DigestQueueEntryRepository

    @MockitoBean
    private lateinit var javaMailSender: JavaMailSender

    private fun subscriber(marker: String): Subscriber =
        subscriberRepository.save(Subscriber(email = "digest-e2e-$marker@example.co.zw"))

    private fun tender(marker: String, title: String): Tender = tenderRepository.save(
        Tender(
            title = title,
            issuingAuthority = "Ministry of Health ($marker)",
            sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR-97-$marker",
            sourceName = "egp.praz.org.zw"
        )
    )

    private fun profile(subscriber: Subscriber, name: String): InterestProfile =
        interestProfileRepository.save(InterestProfile(subscriber = subscriber, name = name))

    private fun queueEntry(subscriber: Subscriber, tender: Tender, profile: InterestProfile): DigestQueueEntry =
        digestQueueEntryRepository.save(DigestQueueEntry(subscriber = subscriber, tender = tender, profile = profile))

    /** Every [SimpleMailMessage] sent to [javaMailSender] so far in this test, addressed to [email]. */
    private fun messagesSentTo(email: String): List<SimpleMailMessage> {
        val captor = ArgumentCaptor.forClass(SimpleMailMessage::class.java)
        verify(javaMailSender, atLeast(0)).send(captor.capture())
        return captor.allValues.filter { it.to?.contains(email) == true }
    }

    /**
     * Test case 1 (issue #97): 2+ queued entries across different subscribers/tenders ->
     * digest emails built and "sent" with correct, fully-resolved content per subscriber, no
     * `LazyInitializationException`, no proxy-related content gaps (e.g. a blank title/authority
     * where a `HibernateProxy`'s uninitialized toString would otherwise leak through).
     */
    @Test
    fun `runDigestCycle sends fully-resolved digest content per subscriber against real JPA entities`() {
        val marker = "t1-${UUID.randomUUID()}"
        val subA = subscriber("a-$marker")
        val subB = subscriber("b-$marker")
        val tenderA = tender("$marker-a", "Supply of Laboratory Equipment ($marker)")
        val tenderB = tender("$marker-b", "Road Resurfacing Works ($marker)")
        val profileA = profile(subA, "Healthcare Profile $marker")
        val profileB = profile(subB, "Roadworks Profile $marker")
        val entryA = queueEntry(subA, tenderA, profileA)
        val entryB = queueEntry(subB, tenderB, profileB)

        // Must not throw -- in particular, must not throw LazyInitializationException while
        // building either subscriber's email body from their (real, lazily-fetched) associations.
        val result = digestService.runDigestCycle()

        assertTrue(result.subscribersDigested >= 2, "expected at least both test subscribers to be digested")

        val messagesA = messagesSentTo(subA.email)
        val messagesB = messagesSentTo(subB.email)
        assertEquals(1, messagesA.size, "expected exactly one digest email sent to subscriber A")
        assertEquals(1, messagesB.size, "expected exactly one digest email sent to subscriber B")

        // Content built from freshly-loaded, non-proxy associations -- the actual regression
        // guard: a future fetch/entity-mapping change that reintroduced the lazy-loading risk
        // flagged during TP-013's review would make these assertions fail (or throw before ever
        // reaching them).
        val bodyA = messagesA.single().text!!
        assertTrue(bodyA.contains(tenderA.title), "subscriber A's digest must contain their tender's title")
        assertTrue(bodyA.contains(tenderA.issuingAuthority), "subscriber A's digest must contain their tender's issuing authority")
        assertTrue(bodyA.contains(tenderA.sourceUrl), "subscriber A's digest must contain their tender's official source link")
        assertTrue(bodyA.contains(profileA.name), "subscriber A's digest must attribute the matched profile")
        assertFalse(bodyA.contains(tenderB.title), "subscriber A's digest must not include subscriber B's tender")

        val bodyB = messagesB.single().text!!
        assertTrue(bodyB.contains(tenderB.title), "subscriber B's digest must contain their tender's title")
        assertTrue(bodyB.contains(tenderB.issuingAuthority), "subscriber B's digest must contain their tender's issuing authority")
        assertTrue(bodyB.contains(tenderB.sourceUrl), "subscriber B's digest must contain their tender's official source link")
        assertTrue(bodyB.contains(profileB.name), "subscriber B's digest must attribute the matched profile")
        assertFalse(bodyB.contains(tenderA.title), "subscriber B's digest must not include subscriber A's tender")

        // Real DB state, not just the in-memory DigestResult count.
        assertNotNull(
            digestQueueEntryRepository.findById(entryA.id).orElseThrow().digestedAt,
            "subscriber A's entry must be marked digested in the database"
        )
        assertNotNull(
            digestQueueEntryRepository.findById(entryB.id).orElseThrow().digestedAt,
            "subscriber B's entry must be marked digested in the database"
        )
    }

    /**
     * Test case 2 (issue #97): entries already marked digested are excluded from a subsequent
     * cycle run -- regression guard for existing behavior
     * ([DigestQueueEntryRepository.findAllByDigestedAtIsNull]), exercised at this same
     * `@SpringBootTest`/real-repository level rather than only against a mocked repository.
     */
    @Test
    fun `entries already marked digested are excluded from a subsequent runDigestCycle call`() {
        val marker = "t2-${UUID.randomUUID()}"
        val sub = subscriber("regression-$marker")
        val tender = tender(marker, "Supply of ICT Equipment ($marker)")
        val profile = profile(sub, "IT Profile $marker")
        val entry = queueEntry(sub, tender, profile)

        val firstResult = digestService.runDigestCycle()
        assertTrue(firstResult.subscribersDigested >= 1)

        val firstMessages = messagesSentTo(sub.email)
        assertEquals(1, firstMessages.size, "expected exactly one digest email on the first run")
        assertNotNull(digestQueueEntryRepository.findById(entry.id).orElseThrow().digestedAt)

        clearInvocations(javaMailSender)

        digestService.runDigestCycle()

        val secondMessages = messagesSentTo(sub.email)
        assertTrue(secondMessages.isEmpty(), "an already-digested entry must not be re-sent on the next cycle")
        // The now-digested entry contributes nothing to this second run's own subscriber count.
        assertFalse(
            digestQueueEntryRepository.findAllByDigestedAtIsNull().any { it.id == entry.id },
            "the already-digested entry must not reappear in the undigested query on a later run"
        )
    }
}
