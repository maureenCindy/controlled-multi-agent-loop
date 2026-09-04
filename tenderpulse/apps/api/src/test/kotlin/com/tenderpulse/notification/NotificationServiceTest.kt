package com.tenderpulse.notification

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.tenderpulse.domain.*
import com.tenderpulse.matching.MatchingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

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

        val body = buildAlertBody(t, "https://api.tenderpulse.example/api/v1/unsubscribe?token=raw")

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

        val body = buildAlertBody(t, "https://api.tenderpulse.example/api/v1/unsubscribe?token=raw")

        assertTrue(body.contains("n/a"))
    }

    /** TP-057: every alert email must include a working, no-login-required unsubscribe link. */
    @Test
    fun `alert body includes the unsubscribe link`() {
        val t = Tender(
            title = "Road maintenance works",
            issuingAuthority = "Ministry of Transport",
            sourceUrl = "https://egp.praz.org.zw/tender/456",
            sourceName = "praz-egp"
        )
        val unsubscribeLink = "https://api.tenderpulse.example/api/v1/unsubscribe?token=raw-unsub-token"

        val body = buildAlertBody(t, unsubscribeLink)

        assertTrue(body.contains(unsubscribeLink), "should include the unsubscribe link")
    }

    /**
     * TP-090: EmailNotificationSender is no longer a log-only scaffold — it now calls
     * [com.tenderpulse.auth.UnsubscribeService.buildUnsubscribeLink] and sends a real email via
     * [JavaMailSender]. This supersedes the TP-083 "succeeds without minting an unsubscribe
     * token" test: #83 removed the call because it was discarded and orphaned DB rows with no
     * consumer; this task is the real consumer the call now feeds into, via [buildAlertBody] and
     * an actual outbound message.
     */
    @Test
    fun `send builds a real email containing tender content and the unsubscribe link`() {
        val unsubscribeService = mockk<com.tenderpulse.auth.UnsubscribeService>()
        val mailSender = mockk<JavaMailSender>()
        val t = Tender(
            title = "IT equipment supply",
            issuingAuthority = "Zimbabwe Revenue Authority",
            sourceUrl = "https://egp.praz.org.zw/tender/789",
            sourceName = "praz-egp"
        )
        val sub = Subscriber(email = "biz@example.co.zw")
        val prof = InterestProfile(subscriber = sub)
        val unsubscribeLink = "https://api.tenderpulse.example/api/v1/unsubscribe?token=raw-unsub-token"

        every { unsubscribeService.buildUnsubscribeLink(sub) } returns unsubscribeLink
        val messageSlot = slot<SimpleMailMessage>()
        every { mailSender.send(capture(messageSlot)) } returns Unit

        val sender = EmailNotificationSender(mailSender, unsubscribeService)
        val result = sender.send(sub, t, prof)

        assertTrue(result.success)
        assertNull(result.error)
        verify(exactly = 1) { unsubscribeService.buildUnsubscribeLink(sub) }
        verify(exactly = 1) { mailSender.send(any<SimpleMailMessage>()) }

        val sentMessage = messageSlot.captured
        assertTrue(sentMessage.to!!.contains(sub.email))
        assertTrue(sentMessage.text!!.contains(t.issuingAuthority), "should attribute the issuing authority")
        assertTrue(sentMessage.text!!.contains(t.sourceUrl), "should link to the official source")
        assertTrue(sentMessage.text!!.contains(unsubscribeLink), "should include the unsubscribe link")
    }

    /**
     * TP-083: the log line must still identify which tender/subscriber the alert was for, but
     * must never leak the raw unsubscribe token/link (which grants unauthenticated unsubscribe
     * access to anyone who reads the logs) — this guarantee must hold even now that a real
     * unsubscribe link is built and embedded in the sent email (TP-090).
     */
    @Test
    fun `send logs tender and subscriber identifiers but never the unsubscribe link or token`() {
        val unsubscribeService = mockk<com.tenderpulse.auth.UnsubscribeService>()
        val mailSender = mockk<JavaMailSender>()
        val t = Tender(
            title = "Road resurfacing works",
            issuingAuthority = "Ministry of Transport",
            sourceUrl = "https://egp.praz.org.zw/tender/999",
            sourceName = "praz-egp"
        )
        val sub = Subscriber(email = "watcher@example.co.zw")
        val prof = InterestProfile(subscriber = sub)
        val rawToken = "super-secret-unsub-token-that-would-be-embedded-if-a-link-were-built"
        val unsubscribeLink = "https://api.tenderpulse.example/api/v1/unsubscribe?token=$rawToken"

        every { unsubscribeService.buildUnsubscribeLink(sub) } returns unsubscribeLink
        every { mailSender.send(any<SimpleMailMessage>()) } returns Unit

        val logger = LoggerFactory.getLogger(EmailNotificationSender::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)

        try {
            val sender = EmailNotificationSender(mailSender, unsubscribeService)
            sender.send(sub, t, prof)
        } finally {
            logger.detachAppender(appender)
        }

        val messages = appender.list.map { it.formattedMessage }
        assertTrue(messages.isNotEmpty(), "expected the EMAIL send to log something")
        for (message in messages) {
            assertFalse(message.contains(rawToken), "log line must not contain the raw unsubscribe token: $message")
            assertFalse(message.contains("unsubscribe", ignoreCase = true), "log line must not reference the unsubscribe link: $message")
        }
        assertTrue(
            messages.any { it.contains(t.id.toString()) && it.contains(sub.id.toString()) },
            "log line should still identify which tender/subscriber the alert was for"
        )
    }

    /**
     * TP-090 AC: "A send failure is logged and does not crash the notification cycle." Mirrors
     * [com.tenderpulse.auth.SmtpMagicLinkMailSenderTest]'s equivalent failure-swallowing test for
     * the magic-link sender.
     */
    @Test
    fun `send reports failure and does not throw when the mail sender fails`() {
        val unsubscribeService = mockk<com.tenderpulse.auth.UnsubscribeService>()
        val mailSender = mockk<JavaMailSender>()
        val t = Tender(
            title = "Fleet maintenance contract",
            issuingAuthority = "Ministry of Transport",
            sourceUrl = "https://egp.praz.org.zw/tender/321",
            sourceName = "praz-egp"
        )
        val sub = Subscriber(email = "fails@example.co.zw")
        val prof = InterestProfile(subscriber = sub)

        every { unsubscribeService.buildUnsubscribeLink(sub) } returns
            "https://api.tenderpulse.example/api/v1/unsubscribe?token=irrelevant"
        every { mailSender.send(any<SimpleMailMessage>()) } throws object : MailException("smtp down") {}

        val sender = EmailNotificationSender(mailSender, unsubscribeService)

        // Should not throw.
        val result = sender.send(sub, t, prof)

        assertFalse(result.success)
        assertNotNull(result.error)
    }
}
