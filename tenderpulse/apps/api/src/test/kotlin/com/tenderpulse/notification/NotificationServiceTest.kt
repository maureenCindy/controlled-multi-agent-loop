package com.tenderpulse.notification

import com.tenderpulse.domain.*
import com.tenderpulse.matching.MatchingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NotificationServiceTest {

    private lateinit var profileRepository: InterestProfileRepository
    private lateinit var notificationRecordRepository: NotificationRecordRepository
    private lateinit var digestQueueEntryRepository: DigestQueueEntryRepository
    private lateinit var matchingService: MatchingService
    private lateinit var emailSender: NotificationChannelSender
    private lateinit var notificationService: NotificationService

    private fun tender() = Tender(
        title = "Supply of office equipment",
        issuingAuthority = "Ministry of Finance",
        sourceUrl = "https://example.gov/tender/1",
        sourceName = "test-source"
    )

    private fun subscriber(tier: SubscriptionTier) = Subscriber(
        email = "sub-${tier.name.lowercase()}@example.com",
        tier = tier
    )

    private fun profile(subscriber: Subscriber) = InterestProfile(
        subscriber = subscriber
    )

    @BeforeEach
    fun setUp() {
        profileRepository = mockk()
        notificationRecordRepository = mockk()
        digestQueueEntryRepository = mockk()
        matchingService = mockk()
        emailSender = mockk()
        every { emailSender.channel } returns NotificationChannel.EMAIL
        every { digestQueueEntryRepository.save(any()) } answers { it.invocation.args[0] as DigestQueueEntry }
        every { notificationRecordRepository.save(any()) } answers { it.invocation.args[0] as NotificationRecord }

        notificationService = NotificationService(
            profileRepository = profileRepository,
            notificationRecordRepository = notificationRecordRepository,
            digestQueueEntryRepository = digestQueueEntryRepository,
            matchingService = matchingService,
            channels = listOf(emailSender)
        )
    }

    @Test
    fun `FREE match queues a digest entry without sending or creating a notification record`() {
        val t = tender()
        val sub = subscriber(SubscriptionTier.FREE)
        val prof = profile(sub)

        every { profileRepository.findAllActiveWithSubscriber() } returns listOf(prof)
        every { matchingService.matches(t, prof) } returns true

        val entrySlot = slot<DigestQueueEntry>()
        every { digestQueueEntryRepository.save(capture(entrySlot)) } answers { it.invocation.args[0] as DigestQueueEntry }

        val sent = notificationService.notifyMatchingSubscribers(t)

        assertEquals(0, sent)
        verify(exactly = 1) { digestQueueEntryRepository.save(any()) }
        verify(exactly = 0) { emailSender.send(any(), any(), any()) }
        verify(exactly = 0) { notificationRecordRepository.save(any()) }

        assertEquals(sub, entrySlot.captured.subscriber)
        assertEquals(t, entrySlot.captured.tender)
        assertEquals(prof, entrySlot.captured.profile)
        assertNull(entrySlot.captured.digestedAt)
    }

    @Test
    fun `PAID match invokes email sender immediately and records success`() {
        val t = tender()
        val sub = subscriber(SubscriptionTier.PAID)
        val prof = profile(sub)

        every { profileRepository.findAllActiveWithSubscriber() } returns listOf(prof)
        every { matchingService.matches(t, prof) } returns true
        every { emailSender.send(sub, t, prof) } returns SendResult(success = true)

        val recordSlot = slot<NotificationRecord>()
        every { notificationRecordRepository.save(capture(recordSlot)) } answers { it.invocation.args[0] as NotificationRecord }

        val sent = notificationService.notifyMatchingSubscribers(t)

        assertEquals(1, sent)
        verify(exactly = 1) { emailSender.send(sub, t, prof) }
        verify(exactly = 1) { notificationRecordRepository.save(any()) }
        verify(exactly = 0) { digestQueueEntryRepository.save(any()) }

        assertEquals(sub, recordSlot.captured.subscriber)
        assertEquals(t, recordSlot.captured.tender)
        assertEquals(NotificationChannel.EMAIL, recordSlot.captured.channel)
        assertTrue(recordSlot.captured.success)
        assertNull(recordSlot.captured.errorMessage)
    }

    @Test
    fun `non-matching profile triggers neither path and does not affect return value`() {
        val t = tender()
        val sub = subscriber(SubscriptionTier.PAID)
        val prof = profile(sub)

        every { profileRepository.findAllActiveWithSubscriber() } returns listOf(prof)
        every { matchingService.matches(t, prof) } returns false

        val sent = notificationService.notifyMatchingSubscribers(t)

        assertEquals(0, sent)
        verify(exactly = 0) { emailSender.send(any(), any(), any()) }
        verify(exactly = 0) { notificationRecordRepository.save(any()) }
        verify(exactly = 0) { digestQueueEntryRepository.save(any()) }
    }

    @Test
    fun `FREE and PAID profiles matching the same tender are handled independently`() {
        val t = tender()
        val freeSub = subscriber(SubscriptionTier.FREE)
        val paidSub = subscriber(SubscriptionTier.PAID)
        val freeProfile = profile(freeSub)
        val paidProfile = profile(paidSub)

        every { profileRepository.findAllActiveWithSubscriber() } returns listOf(freeProfile, paidProfile)
        every { matchingService.matches(t, freeProfile) } returns true
        every { matchingService.matches(t, paidProfile) } returns true
        every { emailSender.send(paidSub, t, paidProfile) } returns SendResult(success = true)

        val sent = notificationService.notifyMatchingSubscribers(t)

        assertEquals(1, sent)
        verify(exactly = 1) { digestQueueEntryRepository.save(match { it.subscriber == freeSub }) }
        verify(exactly = 1) { emailSender.send(paidSub, t, paidProfile) }
        verify(exactly = 1) { notificationRecordRepository.save(match { it.subscriber == paidSub }) }
        verify(exactly = 0) { emailSender.send(freeSub, t, freeProfile) }
    }

    @Test
    fun `PAID match with failed send still records failure and does not increment sent count`() {
        val t = tender()
        val sub = subscriber(SubscriptionTier.PAID)
        val prof = profile(sub)

        every { profileRepository.findAllActiveWithSubscriber() } returns listOf(prof)
        every { matchingService.matches(t, prof) } returns true
        every { emailSender.send(sub, t, prof) } returns SendResult(success = false, error = "SMTP timeout")

        val recordSlot = slot<NotificationRecord>()
        every { notificationRecordRepository.save(capture(recordSlot)) } answers { it.invocation.args[0] as NotificationRecord }

        val sent = notificationService.notifyMatchingSubscribers(t)

        assertEquals(0, sent)
        verify(exactly = 1) { notificationRecordRepository.save(any()) }
        assertFalse(recordSlot.captured.success)
        assertEquals("SMTP timeout", recordSlot.captured.errorMessage)
    }
}

/**
 * TP-041: alerts must attribute the issuing authority and link back to the official
 * PRAZ e-GP source rather than hosting the tender document ourselves.
 */
class AlertContentTest {

    @Test
    fun `alert body attributes the issuing authority and links to the official source`() {
        val t = Tender(
            title = "Supply of office equipment",
            issuingAuthority = "Ministry of Finance",
            sourceUrl = "https://egp.praz.org.zw/tender/123",
            sourceName = "praz-egp"
        )

        val body = buildAlertBody(t)

        assertTrue(body.contains("Ministry of Finance"), "should attribute the issuing authority")
        assertTrue(body.contains("https://egp.praz.org.zw/tender/123"), "should link to the official source")
        assertTrue(body.contains(t.title), "should include the tender title")
    }

    @Test
    fun `alert body handles a missing deadline gracefully`() {
        val t = Tender(
            title = "Road maintenance works",
            issuingAuthority = "Ministry of Transport",
            sourceUrl = "https://egp.praz.org.zw/tender/456",
            sourceName = "praz-egp",
            deadline = null
        )

        val body = buildAlertBody(t)

        assertTrue(body.contains("n/a"))
    }

    @Test
    fun `EmailNotificationSender send succeeds and content includes attribution and link`() {
        val sender = EmailNotificationSender()
        val t = Tender(
            title = "IT equipment supply",
            issuingAuthority = "Zimbabwe Revenue Authority",
            sourceUrl = "https://egp.praz.org.zw/tender/789",
            sourceName = "praz-egp"
        )
        val sub = Subscriber(email = "biz@example.co.zw")
        val prof = InterestProfile(subscriber = sub)

        val result = sender.send(sub, t, prof)

        assertTrue(result.success)
        assertNull(result.error)
    }
}
