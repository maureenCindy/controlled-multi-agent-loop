package com.tenderpulse.notification

import com.tenderpulse.domain.DigestQueueEntry
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.Tender
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

/**
 * [SmtpDigestMailSender] never touches the network in tests — [JavaMailSender] is a mockk mock,
 * matching "no live network in unit tests" (CONTRIBUTING.md), same principle as
 * [com.tenderpulse.auth.SmtpMagicLinkMailSenderTest].
 */
class DigestMailSenderTest {

    private val javaMailSender = mockk<JavaMailSender>(relaxed = true)
    private val sender = SmtpDigestMailSender(javaMailSender)
    private val subscriber = Subscriber(email = "sub@example.com")

    private fun entry(title: String, sourceUrl: String) = DigestQueueEntry(
        subscriber = subscriber,
        tender = Tender(
            title = title,
            issuingAuthority = "Ministry of Finance",
            sourceUrl = sourceUrl,
            sourceName = "praz-egp"
        ),
        profile = InterestProfile(subscriber = subscriber)
    )

    @Test
    fun `sends one message to the subscriber listing every entry`() {
        val entries = listOf(
            entry("Tender A", "https://egp.praz.org.zw/tender/1"),
            entry("Tender B", "https://egp.praz.org.zw/tender/2"),
            entry("Tender C", "https://egp.praz.org.zw/tender/3")
        )
        val captured = slot<SimpleMailMessage>()
        every { javaMailSender.send(capture(captured)) } returns Unit

        val result = sender.sendDigest(subscriber, entries, "https://api.example.com/api/v1/unsubscribe?token=abc")

        assertTrue(result)
        assertTrue(captured.captured.to!!.contains("sub@example.com"))
        val body = captured.captured.text!!
        assertTrue(body.contains("Tender A"))
        assertTrue(body.contains("Tender B"))
        assertTrue(body.contains("Tender C"))
        assertTrue(body.contains("https://api.example.com/api/v1/unsubscribe?token=abc"))
        verify(exactly = 1) { javaMailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `a mail sender failure is swallowed and reported as an unsuccessful send`() {
        every { javaMailSender.send(any<SimpleMailMessage>()) } throws object : MailException("smtp down") {}

        val result = sender.sendDigest(
            subscriber,
            listOf(entry("Tender A", "https://egp.praz.org.zw/tender/1")),
            "https://api.example.com/api/v1/unsubscribe?token=abc"
        )

        assertFalse(result)
    }

    @Test
    fun `digest body attributes each tender's issuing authority and official source`() {
        val entries = listOf(entry("Road resurfacing works", "https://egp.praz.org.zw/tender/456"))

        val body = buildDigestEmailBody(entries, "https://api.example.com/api/v1/unsubscribe?token=abc")

        assertTrue(body.contains("Road resurfacing works"))
        assertTrue(body.contains("Ministry of Finance"))
        assertTrue(body.contains("https://egp.praz.org.zw/tender/456"))
    }

    @Test
    fun `digest body includes exactly one unsubscribe link regardless of entry count`() {
        val entries = listOf(
            entry("Tender A", "https://egp.praz.org.zw/tender/1"),
            entry("Tender B", "https://egp.praz.org.zw/tender/2")
        )
        val unsubscribeLink = "https://api.example.com/api/v1/unsubscribe?token=only-once"

        val body = buildDigestEmailBody(entries, unsubscribeLink)

        val occurrences = Regex(Regex.escape(unsubscribeLink)).findAll(body).count()
        assertTrue(occurrences == 1, "expected exactly one unsubscribe link, found $occurrences")
    }
}
