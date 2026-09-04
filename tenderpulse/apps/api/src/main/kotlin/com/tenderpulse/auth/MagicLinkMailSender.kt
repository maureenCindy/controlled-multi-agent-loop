package com.tenderpulse.auth

import com.tenderpulse.domain.Subscriber
import org.slf4j.LoggerFactory
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/**
 * Sends the magic-link sign-in email (TP-038). Reuses the existing `spring-boot-starter-mail`
 * dependency and [JavaMailSender] bean, same as the rest of the app's mail wiring — see
 * [com.tenderpulse.notification.NotificationService] for the equivalent pattern on the
 * notification side.
 */
interface MagicLinkMailSender {
    fun sendMagicLink(subscriber: Subscriber, verifyLink: String)
}

@Service
class SmtpMagicLinkMailSender(private val mailSender: JavaMailSender) : MagicLinkMailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendMagicLink(subscriber: Subscriber, verifyLink: String) {
        val message = SimpleMailMessage().apply {
            setTo(subscriber.email)
            subject = "Your TenderPulse sign-in link"
            text = buildMagicLinkEmailBody(verifyLink)
        }
        runCatching { mailSender.send(message) }
            .onFailure { log.warn("Failed to send magic-link email to {}: {}", subscriber.email, it.message) }
    }
}

/**
 * Builds the outbound magic-link email content, pulled out as a standalone function (same
 * reasoning as [com.tenderpulse.notification.buildAlertBody] from TP-041) so the copy is
 * independently unit-testable without a mail sender.
 */
fun buildMagicLinkEmailBody(verifyLink: String): String =
    "Sign in to TenderPulse using the link below. This link is valid for 24 hours and can only " +
        "be used once.\n\n$verifyLink\n\nIf you didn't request this, you can safely ignore this email."
