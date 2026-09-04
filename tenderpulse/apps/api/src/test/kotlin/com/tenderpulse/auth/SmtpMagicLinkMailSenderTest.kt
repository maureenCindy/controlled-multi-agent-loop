package com.tenderpulse.auth

import com.tenderpulse.domain.Subscriber
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mail.MailException
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender

/**
 * [SmtpMagicLinkMailSender] never touches the network in tests — [JavaMailSender] is a mockk
 * mock, matching "no live network in unit tests" (CONTRIBUTING.md) for the mail-sending path,
 * same principle as the PRAZ adapter's fixture-only HTTP tests.
 */
class SmtpMagicLinkMailSenderTest {

    private val javaMailSender = mockk<JavaMailSender>(relaxed = true)
    private val sender = SmtpMagicLinkMailSender(javaMailSender)
    private val subscriber = Subscriber(email = "sub@example.com")

    @Test
    fun `sends a message to the subscriber containing the verify link`() {
        val captured = slot<SimpleMailMessage>()
        every { javaMailSender.send(capture(captured)) } returns Unit

        sender.sendMagicLink(subscriber, "https://api.example.com/api/v1/auth/verify?token=abc123")

        assertTrue(captured.captured.to!!.contains("sub@example.com"))
        assertTrue(captured.captured.text!!.contains("https://api.example.com/api/v1/auth/verify?token=abc123"))
        verify(exactly = 1) { javaMailSender.send(any<SimpleMailMessage>()) }
    }

    @Test
    fun `a mail sender failure is swallowed rather than propagated`() {
        every { javaMailSender.send(any<SimpleMailMessage>()) } throws object : MailException("smtp down") {}

        // Should not throw.
        sender.sendMagicLink(subscriber, "https://api.example.com/verify?token=abc123")
    }

    @Test
    fun `email body states the 24h single-use window`() {
        val body = buildMagicLinkEmailBody("https://api.example.com/verify?token=abc123")

        assertTrue(body.contains("24 hours"))
        assertTrue(body.contains("once"))
        assertTrue(body.contains("https://api.example.com/verify?token=abc123"))
    }
}
