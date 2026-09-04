package com.tenderpulse.notification

import com.tenderpulse.domain.DeadlineReminderRecord
import com.tenderpulse.domain.DeadlineReminderRecordRepository
import com.tenderpulse.domain.DigestQueueEntry
import com.tenderpulse.domain.DigestQueueEntryRepository
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.NotificationRecord
import com.tenderpulse.domain.NotificationRecordRepository
import com.tenderpulse.domain.NotificationChannel
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

/**
 * TP-056 (issue #56): covers all 6 test cases from the issue for the deadline-reminder job, plus
 * the "no candidate tenders" and scheduled-toggle cases. Real window-boundary enforcement (the
 * mechanism behind test cases 4/5) is proven empirically at the repository layer in
 * [com.tenderpulse.domain.TenderRepositoryTest] against a real H2 context; here
 * [TenderRepository.findByDeadlineBetween] is mocked, since [ReminderService] itself only needs
 * to trust whatever that query returns.
 */
class ReminderServiceTest {

    private lateinit var tenderRepository: TenderRepository
    private lateinit var notificationRecordRepository: NotificationRecordRepository
    private lateinit var digestQueueEntryRepository: DigestQueueEntryRepository
    private lateinit var deadlineReminderRecordRepository: DeadlineReminderRecordRepository
    private lateinit var profileRepository: InterestProfileRepository
    private lateinit var emailNotificationSender: EmailNotificationSender
    private lateinit var reminderService: ReminderService

    private fun tender(deadline: Instant) = Tender(
        title = "Supply of office equipment",
        issuingAuthority = "Ministry of Finance",
        sourceUrl = "https://egp.praz.org.zw/tender/1",
        sourceName = "praz-egp",
        deadline = deadline
    )

    private fun subscriber(tier: SubscriptionTier) = Subscriber(
        email = "sub-${tier.name.lowercase()}@example.com",
        tier = tier
    )

    private fun profile(subscriber: Subscriber) = InterestProfile(subscriber = subscriber)

    @BeforeEach
    fun setUp() {
        tenderRepository = mockk()
        notificationRecordRepository = mockk()
        digestQueueEntryRepository = mockk()
        deadlineReminderRecordRepository = mockk()
        profileRepository = mockk()
        emailNotificationSender = mockk()

        every { deadlineReminderRecordRepository.save(any()) } answers { it.invocation.args[0] as DeadlineReminderRecord }
        every { digestQueueEntryRepository.save(any()) } answers { it.invocation.args[0] as DigestQueueEntry }

        reminderService = ReminderService(
            tenderRepository = tenderRepository,
            notificationRecordRepository = notificationRecordRepository,
            digestQueueEntryRepository = digestQueueEntryRepository,
            deadlineReminderRecordRepository = deadlineReminderRecordRepository,
            profileRepository = profileRepository,
            emailNotificationSender = emailNotificationSender,
            windowDays = 3,
            scheduledEnabled = false
        )
    }

    // 1. Tender deadline in 2 days, subscriber previously matched via Paid ->
    //    immediate reminder email sent, tracking record created.
    @Test
    fun `Paid subscriber previously notified receives an immediate reminder email and a tracking record is created`() {
        val t = tender(Instant.now().plus(Duration.ofDays(2)))
        val sub = subscriber(SubscriptionTier.PAID)
        val prof = profile(sub)

        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns listOf(t)
        every { notificationRecordRepository.findByTenderIdAndSuccessTrue(t.id) } returns listOf(
            NotificationRecord(subscriber = sub, tender = t, channel = NotificationChannel.EMAIL, success = true)
        )
        every { digestQueueEntryRepository.findByTenderId(t.id) } returns emptyList()
        every { deadlineReminderRecordRepository.existsBySubscriberIdAndTenderId(sub.id, t.id) } returns false
        every { profileRepository.findBySubscriberIdAndActiveTrue(sub.id) } returns listOf(prof)
        every { emailNotificationSender.send(sub, t, prof) } returns SendResult(success = true)

        val recordSlot = slot<DeadlineReminderRecord>()
        every { deadlineReminderRecordRepository.save(capture(recordSlot)) } answers { it.invocation.args[0] as DeadlineReminderRecord }

        val result = reminderService.runReminderCycle()

        assertEquals(1, result.remindersSent)
        assertEquals(0, result.digestEntriesQueued)
        verify(exactly = 1) { emailNotificationSender.send(sub, t, prof) }
        verify(exactly = 1) { deadlineReminderRecordRepository.save(any()) }
        assertEquals(sub, recordSlot.captured.subscriber)
        assertEquals(t, recordSlot.captured.tender)
    }

    // 2. Same tender, subsequent run -> no duplicate reminder.
    //
    // profileRepository/emailNotificationSender are stubbed as if the dedup check were *not* in
    // place (rather than left unstubbed), so this test cannot pass "by accident" via an unrelated
    // MockKException on an unstubbed call being silently swallowed by ReminderService's
    // per-subscriber try/catch — it can only pass if the dedup check itself actually short-circuits
    // before those calls. Confirmed empirically: temporarily short-circuiting the dedup check
    // (`if (false && deadlineReminderRecordRepository.existsBySubscriberIdAndTenderId(...))`) made
    // this exact test fail on `assertEquals(1, result.remindersSent)`-equivalent grounds
    // (result.remindersSent became 1 and result.failed stayed 0) before the fix was restored.
    @Test
    fun `no duplicate reminder is sent when a tracking record already exists`() {
        val t = tender(Instant.now().plus(Duration.ofDays(2)))
        val sub = subscriber(SubscriptionTier.PAID)
        val prof = profile(sub)

        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns listOf(t)
        every { notificationRecordRepository.findByTenderIdAndSuccessTrue(t.id) } returns listOf(
            NotificationRecord(subscriber = sub, tender = t, channel = NotificationChannel.EMAIL, success = true)
        )
        every { digestQueueEntryRepository.findByTenderId(t.id) } returns emptyList()
        every { deadlineReminderRecordRepository.existsBySubscriberIdAndTenderId(sub.id, t.id) } returns true
        every { profileRepository.findBySubscriberIdAndActiveTrue(sub.id) } returns listOf(prof)
        every { emailNotificationSender.send(sub, t, prof) } returns SendResult(success = true)

        val result = reminderService.runReminderCycle()

        assertEquals(0, result.remindersSent)
        assertEquals(0, result.digestEntriesQueued)
        assertEquals(0, result.failed)
        verify(exactly = 0) { emailNotificationSender.send(any(), any(), any()) }
        verify(exactly = 0) { deadlineReminderRecordRepository.save(any()) }
    }

    // 3. Tender deadline in 2 days, subscriber previously matched via Free digest ->
    //    new DigestQueueEntry created.
    @Test
    fun `Free subscriber previously notified via digest gets a new digest queue entry`() {
        val t = tender(Instant.now().plus(Duration.ofDays(2)))
        val sub = subscriber(SubscriptionTier.FREE)
        val prof = profile(sub)
        val originalEntry = DigestQueueEntry(subscriber = sub, tender = t, profile = prof, digestedAt = Instant.now())

        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns listOf(t)
        every { notificationRecordRepository.findByTenderIdAndSuccessTrue(t.id) } returns emptyList()
        every { digestQueueEntryRepository.findByTenderId(t.id) } returns listOf(originalEntry)
        every { deadlineReminderRecordRepository.existsBySubscriberIdAndTenderId(sub.id, t.id) } returns false

        val entrySlot = slot<DigestQueueEntry>()
        every { digestQueueEntryRepository.save(capture(entrySlot)) } answers { it.invocation.args[0] as DigestQueueEntry }

        val result = reminderService.runReminderCycle()

        assertEquals(0, result.remindersSent)
        assertEquals(1, result.digestEntriesQueued)
        verify(exactly = 1) { digestQueueEntryRepository.save(any()) }
        verify(exactly = 1) { deadlineReminderRecordRepository.save(any()) }
        verify(exactly = 0) { emailNotificationSender.send(any(), any(), any()) }
        assertEquals(sub, entrySlot.captured.subscriber)
        assertEquals(t, entrySlot.captured.tender)
        assertEquals(prof, entrySlot.captured.profile)
        // A brand new queue entry for the reminder, not digested yet.
        assertEquals(null, entrySlot.captured.digestedAt)
    }

    // 4. Tender deadline in 10 days (outside window) -> no reminder.
    // 5. Tender deadline already passed -> no reminder.
    // Both are enforced by TenderRepository.findByDeadlineBetween's own query boundary (proven
    // empirically in TenderRepositoryTest); at the service level this just confirms
    // ReminderService correctly does nothing when the repository reports no in-window tenders.
    @Test
    fun `no tenders within the window means no reminders are processed at all`() {
        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns emptyList()

        val result = reminderService.runReminderCycle()

        assertEquals(0, result.remindersSent)
        assertEquals(0, result.digestEntriesQueued)
        verify(exactly = 0) { notificationRecordRepository.findByTenderIdAndSuccessTrue(any()) }
        verify(exactly = 0) { digestQueueEntryRepository.findByTenderId(any()) }
    }

    // 6. Subscriber never matched to this tender -> not included in the reminder run.
    @Test
    fun `a subscriber never notified of the original match does not receive a reminder`() {
        val t = tender(Instant.now().plus(Duration.ofDays(2)))

        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns listOf(t)
        every { notificationRecordRepository.findByTenderIdAndSuccessTrue(t.id) } returns emptyList()
        every { digestQueueEntryRepository.findByTenderId(t.id) } returns emptyList()

        val result = reminderService.runReminderCycle()

        assertEquals(0, result.remindersSent)
        assertEquals(0, result.digestEntriesQueued)
        verify(exactly = 0) { deadlineReminderRecordRepository.existsBySubscriberIdAndTenderId(any(), any()) }
        verify(exactly = 0) { emailNotificationSender.send(any(), any(), any()) }
        verify(exactly = 0) { digestQueueEntryRepository.save(any()) }
    }

    // 6b. Subscriber WAS previously matched/notified but has SINCE opted out -> no reminder sent
    //     and no tracking record created, even though the historical NotificationRecord exists.
    @Test
    fun `a Paid subscriber who has since opted out does not receive a reminder`() {
        val t = tender(Instant.now().plus(Duration.ofDays(2)))
        val sub = subscriber(SubscriptionTier.PAID).copy(emailOptOut = true)

        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns listOf(t)
        every { notificationRecordRepository.findByTenderIdAndSuccessTrue(t.id) } returns listOf(
            NotificationRecord(subscriber = sub, tender = t, channel = NotificationChannel.EMAIL, success = true)
        )
        every { digestQueueEntryRepository.findByTenderId(t.id) } returns emptyList()

        val result = reminderService.runReminderCycle()

        assertEquals(0, result.remindersSent)
        assertEquals(0, result.failed)
        verify(exactly = 0) { deadlineReminderRecordRepository.existsBySubscriberIdAndTenderId(any(), any()) }
        verify(exactly = 0) { emailNotificationSender.send(any(), any(), any()) }
        verify(exactly = 0) { deadlineReminderRecordRepository.save(any()) }
    }

    // 6c. Subscriber WAS previously matched/notified via the Free digest queue but has SINCE been
    //     deactivated -> no new digest entry queued and no tracking record created.
    @Test
    fun `a Free subscriber who has since been deactivated does not receive a reminder digest entry`() {
        val t = tender(Instant.now().plus(Duration.ofDays(2)))
        val sub = subscriber(SubscriptionTier.FREE).copy(active = false)
        val prof = profile(sub)
        val originalEntry = DigestQueueEntry(subscriber = sub, tender = t, profile = prof, digestedAt = Instant.now())

        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns listOf(t)
        every { notificationRecordRepository.findByTenderIdAndSuccessTrue(t.id) } returns emptyList()
        every { digestQueueEntryRepository.findByTenderId(t.id) } returns listOf(originalEntry)

        val result = reminderService.runReminderCycle()

        assertEquals(0, result.digestEntriesQueued)
        assertEquals(0, result.failed)
        verify(exactly = 0) { deadlineReminderRecordRepository.existsBySubscriberIdAndTenderId(any(), any()) }
        verify(exactly = 0) { digestQueueEntryRepository.save(any()) }
        verify(exactly = 0) { deadlineReminderRecordRepository.save(any()) }
    }

    // A previous Paid send that failed never actually reached the subscriber, so it shouldn't
    // count as "already told about this tender" for reminder-eligibility purposes.
    @Test
    fun `a failed original Paid send does not make the subscriber eligible for a reminder`() {
        val t = tender(Instant.now().plus(Duration.ofDays(2)))

        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns listOf(t)
        // findByTenderIdAndSuccessTrue only returns successful sends by definition, so a failed
        // original send simply never appears here.
        every { notificationRecordRepository.findByTenderIdAndSuccessTrue(t.id) } returns emptyList()
        every { digestQueueEntryRepository.findByTenderId(t.id) } returns emptyList()

        val result = reminderService.runReminderCycle()

        assertEquals(0, result.remindersSent)
        verify(exactly = 0) { emailNotificationSender.send(any(), any(), any()) }
    }

    @Test
    fun `scheduled reminder cycle is a no-op when disabled`() {
        val disabledService = ReminderService(
            tenderRepository = tenderRepository,
            notificationRecordRepository = notificationRecordRepository,
            digestQueueEntryRepository = digestQueueEntryRepository,
            deadlineReminderRecordRepository = deadlineReminderRecordRepository,
            profileRepository = profileRepository,
            emailNotificationSender = emailNotificationSender,
            windowDays = 3,
            scheduledEnabled = false
        )

        disabledService.scheduledReminderCycle()

        verify(exactly = 0) { tenderRepository.findByDeadlineBetween(any(), any()) }
    }

    @Test
    fun `scheduled reminder cycle runs when enabled`() {
        every { tenderRepository.findByDeadlineBetween(any(), any()) } returns emptyList()
        val enabledService = ReminderService(
            tenderRepository = tenderRepository,
            notificationRecordRepository = notificationRecordRepository,
            digestQueueEntryRepository = digestQueueEntryRepository,
            deadlineReminderRecordRepository = deadlineReminderRecordRepository,
            profileRepository = profileRepository,
            emailNotificationSender = emailNotificationSender,
            windowDays = 3,
            scheduledEnabled = true
        )

        enabledService.scheduledReminderCycle()

        verify(exactly = 1) { tenderRepository.findByDeadlineBetween(any(), any()) }
    }
}
