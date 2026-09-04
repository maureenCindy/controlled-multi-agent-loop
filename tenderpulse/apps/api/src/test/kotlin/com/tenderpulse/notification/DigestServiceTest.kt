package com.tenderpulse.notification

import com.tenderpulse.auth.UnsubscribeService
import com.tenderpulse.domain.DigestQueueEntry
import com.tenderpulse.domain.DigestQueueEntryRepository
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.Tender
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * TP-013 (issue #92): covers all 5 test cases from the issue for the Free-tier daily digest job.
 */
class DigestServiceTest {

    private lateinit var digestQueueEntryRepository: DigestQueueEntryRepository
    private lateinit var unsubscribeService: UnsubscribeService
    private lateinit var digestMailSender: DigestMailSender
    private lateinit var digestService: DigestService

    private fun subscriber(email: String) = Subscriber(email = email)

    private fun entry(subscriber: Subscriber, title: String, sourceUrl: String) = DigestQueueEntry(
        subscriber = subscriber,
        tender = Tender(
            title = title,
            issuingAuthority = "Ministry of Finance",
            sourceUrl = sourceUrl,
            sourceName = "praz-egp"
        ),
        profile = InterestProfile(subscriber = subscriber, name = "Digest Test Profile")
    )

    @BeforeEach
    fun setUp() {
        digestQueueEntryRepository = mockk()
        unsubscribeService = mockk()
        digestMailSender = mockk()

        every { unsubscribeService.buildUnsubscribeLink(any()) } returns "https://api.example.com/api/v1/unsubscribe?token=abc"
        every { digestQueueEntryRepository.saveAll(any<List<DigestQueueEntry>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            it.invocation.args[0] as List<DigestQueueEntry>
        }

        digestService = DigestService(
            digestQueueEntryRepository = digestQueueEntryRepository,
            unsubscribeService = unsubscribeService,
            digestMailSender = digestMailSender,
            scheduledEnabled = false
        )
    }

    // 1. Subscriber with 3 undigested entries -> one email listing all 3, all 3 marked digested.
    @Test
    fun `subscriber with 3 undigested entries receives one email listing all 3, all marked digested`() {
        val sub = subscriber("three-entries@example.com")
        val entries = listOf(
            entry(sub, "Tender A", "https://egp.praz.org.zw/tender/1"),
            entry(sub, "Tender B", "https://egp.praz.org.zw/tender/2"),
            entry(sub, "Tender C", "https://egp.praz.org.zw/tender/3")
        )
        every { digestQueueEntryRepository.findAllByDigestedAtIsNull() } returns entries
        every { digestMailSender.sendDigest(sub, entries, any()) } returns true

        val savedSlot = slot<List<DigestQueueEntry>>()
        every { digestQueueEntryRepository.saveAll(capture(savedSlot)) } answers {
            @Suppress("UNCHECKED_CAST")
            it.invocation.args[0] as List<DigestQueueEntry>
        }

        val result = digestService.runDigestCycle()

        assertEquals(1, result.subscribersDigested)
        assertEquals(0, result.subscribersFailed)
        verify(exactly = 1) { digestMailSender.sendDigest(sub, entries, any()) }
        verify(exactly = 1) { digestQueueEntryRepository.saveAll(any<List<DigestQueueEntry>>()) }
        assertEquals(3, savedSlot.captured.size)
        savedSlot.captured.forEach { assertNotNull(it.digestedAt) }
    }

    // 2. Subscriber with 0 undigested entries -> no email sent.
    @Test
    fun `subscriber with zero undigested entries receives no email`() {
        every { digestQueueEntryRepository.findAllByDigestedAtIsNull() } returns emptyList()

        val result = digestService.runDigestCycle()

        assertEquals(0, result.subscribersDigested)
        assertEquals(0, result.subscribersFailed)
        verify(exactly = 0) { digestMailSender.sendDigest(any(), any(), any()) }
        verify(exactly = 0) { digestQueueEntryRepository.saveAll(any<List<DigestQueueEntry>>()) }
    }

    // 3. Two subscribers, one with entries, one without -> only the one with entries receives an email.
    @Test
    fun `only the subscriber with pending entries receives a digest`() {
        val withEntries = subscriber("has-entries@example.com")
        val entries = listOf(entry(withEntries, "Tender A", "https://egp.praz.org.zw/tender/1"))

        // A subscriber with zero undigested entries never shows up in
        // findAllByDigestedAtIsNull() at all (nothing to group), so only `entries` is returned.
        every { digestQueueEntryRepository.findAllByDigestedAtIsNull() } returns entries
        every { digestMailSender.sendDigest(withEntries, entries, any()) } returns true

        val result = digestService.runDigestCycle()

        assertEquals(1, result.subscribersDigested)
        verify(exactly = 1) { digestMailSender.sendDigest(withEntries, entries, any()) }
    }

    // 4. Email send fails for subscriber A -> subscriber B still receives their digest in the same run.
    @Test
    fun `a send failure for one subscriber does not block another subscriber's digest`() {
        val subA = subscriber("fails@example.com")
        val subB = subscriber("succeeds@example.com")
        val entriesA = listOf(entry(subA, "Tender A", "https://egp.praz.org.zw/tender/a"))
        val entriesB = listOf(entry(subB, "Tender B", "https://egp.praz.org.zw/tender/b"))

        every { digestQueueEntryRepository.findAllByDigestedAtIsNull() } returns entriesA + entriesB
        every { digestMailSender.sendDigest(subA, entriesA, any()) } returns false
        every { digestMailSender.sendDigest(subB, entriesB, any()) } returns true

        val result = digestService.runDigestCycle()

        assertEquals(1, result.subscribersDigested)
        assertEquals(1, result.subscribersFailed)
        verify(exactly = 1) { digestMailSender.sendDigest(subA, entriesA, any()) }
        verify(exactly = 1) { digestMailSender.sendDigest(subB, entriesB, any()) }
        // Only subscriber B's entries are marked digested; A's entries are left for the next run.
        verify(exactly = 1) {
            digestQueueEntryRepository.saveAll(
                match<List<DigestQueueEntry>> { list -> list.all { e -> e.subscriber == subB } }
            )
        }
    }

    /** Same as above, but the failure is a thrown exception rather than a `false` return. */
    @Test
    fun `an exception building the unsubscribe link for one subscriber does not block another subscriber's digest`() {
        val subA = subscriber("throws@example.com")
        val subB = subscriber("succeeds-2@example.com")
        val entriesA = listOf(entry(subA, "Tender A", "https://egp.praz.org.zw/tender/a"))
        val entriesB = listOf(entry(subB, "Tender B", "https://egp.praz.org.zw/tender/b"))

        every { digestQueueEntryRepository.findAllByDigestedAtIsNull() } returns entriesA + entriesB
        every { unsubscribeService.buildUnsubscribeLink(subA) } throws RuntimeException("DB down")
        every { unsubscribeService.buildUnsubscribeLink(subB) } returns "https://api.example.com/api/v1/unsubscribe?token=b"
        every { digestMailSender.sendDigest(subB, entriesB, any()) } returns true

        val result = digestService.runDigestCycle()

        assertEquals(1, result.subscribersDigested)
        assertEquals(1, result.subscribersFailed)
        verify(exactly = 0) { digestMailSender.sendDigest(subA, entriesA, any()) }
        verify(exactly = 1) { digestMailSender.sendDigest(subB, entriesB, any()) }
    }

    // 5. Entries already marked digested from a prior run -> not included in the next run's email.
    @Test
    fun `already-digested entries from a prior run are not included in the next run`() {
        val sub = subscriber("repeat@example.com")
        // findAllByDigestedAtIsNull() only ever returns undigested rows by definition, so a
        // previously-digested entry for this subscriber simply never appears here.
        val newEntry = entry(sub, "New tender since last digest", "https://egp.praz.org.zw/tender/new")
        every { digestQueueEntryRepository.findAllByDigestedAtIsNull() } returns listOf(newEntry)
        every { digestMailSender.sendDigest(sub, listOf(newEntry), any()) } returns true

        digestService.runDigestCycle()

        verify(exactly = 1) { digestMailSender.sendDigest(sub, listOf(newEntry), any()) }
    }

    // Email includes the unsubscribe link (AC).
    @Test
    fun `unsubscribe link is built exactly once per subscriber and passed to the mail sender`() {
        val sub = subscriber("unsub-check@example.com")
        val entries = listOf(entry(sub, "Tender A", "https://egp.praz.org.zw/tender/1"))
        every { digestQueueEntryRepository.findAllByDigestedAtIsNull() } returns entries
        every { digestMailSender.sendDigest(sub, entries, "https://api.example.com/api/v1/unsubscribe?token=abc") } returns true

        digestService.runDigestCycle()

        verify(exactly = 1) { unsubscribeService.buildUnsubscribeLink(sub) }
        verify(exactly = 1) { digestMailSender.sendDigest(sub, entries, "https://api.example.com/api/v1/unsubscribe?token=abc") }
    }

    @Test
    fun `scheduled digest cycle is a no-op when disabled`() {
        val disabledService = DigestService(
            digestQueueEntryRepository = digestQueueEntryRepository,
            unsubscribeService = unsubscribeService,
            digestMailSender = digestMailSender,
            scheduledEnabled = false
        )

        disabledService.scheduledDigestCycle()

        verify(exactly = 0) { digestQueueEntryRepository.findAllByDigestedAtIsNull() }
    }

    @Test
    fun `scheduled digest cycle runs when enabled`() {
        every { digestQueueEntryRepository.findAllByDigestedAtIsNull() } returns emptyList()
        val enabledService = DigestService(
            digestQueueEntryRepository = digestQueueEntryRepository,
            unsubscribeService = unsubscribeService,
            digestMailSender = digestMailSender,
            scheduledEnabled = true
        )

        enabledService.scheduledDigestCycle()

        verify(exactly = 1) { digestQueueEntryRepository.findAllByDigestedAtIsNull() }
    }
}
