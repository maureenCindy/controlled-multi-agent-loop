package com.tenderpulse.notification

import com.tenderpulse.domain.DigestQueueEntry
import com.tenderpulse.domain.Subscriber
import org.slf4j.LoggerFactory
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/**
 * Sends the Free-tier daily digest email (TP-013, issue #92): one email per subscriber
 * summarising every [DigestQueueEntry] queued since their last digest.
 *
 * Reuses the same [JavaMailSender]-based real-send pattern already established by
 * [com.tenderpulse.auth.SmtpMagicLinkMailSender] (TP-038) — unlike
 * [com.tenderpulse.notification.EmailNotificationSender] (still a log-only scaffold for the
 * separate Paid-tier real-time path, see #90), this sends a real email.
 */
interface DigestMailSender {
    /**
     * Sends one digest email listing all of [entries] to [subscriber]. Returns `true` on
     * successful send, `false` otherwise — never throws, mirroring
     * [com.tenderpulse.auth.SmtpMagicLinkMailSender.sendMagicLink]'s "swallow, don't propagate"
     * handling of mail-sender failures, so [DigestService] can rely on the return value alone to
     * decide whether to mark [entries] digested.
     */
    fun sendDigest(subscriber: Subscriber, entries: List<DigestQueueEntry>, unsubscribeLink: String): Boolean
}

@Service
class SmtpDigestMailSender(private val mailSender: JavaMailSender) : DigestMailSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun sendDigest(subscriber: Subscriber, entries: List<DigestQueueEntry>, unsubscribeLink: String): Boolean {
        val message = SimpleMailMessage().apply {
            setTo(subscriber.email)
            subject = "Your TenderPulse daily digest"
            text = buildDigestEmailBody(entries, unsubscribeLink)
        }
        return runCatching { mailSender.send(message) }
            .onFailure { log.warn("Failed to send digest email to {}: {}", subscriber.email, it.message) }
            .isSuccess
    }
}

/**
 * Builds the outbound digest email content: one line per matched tender, in the same
 * attribution style as [buildAlertBody] (issuing authority + official source link — TP-041),
 * followed by a single unsubscribe link for the whole digest (not one per tender, since this is
 * one email covering every accumulated match — see
 * [com.tenderpulse.auth.UnsubscribeService.buildUnsubscribeLink]).
 *
 * Issue #58: each line also attributes which of the subscriber's named interest profiles
 * ([DigestQueueEntry.profile]) triggered that particular match — a subscriber digesting matches
 * from more than one profile in the same email needs to be able to tell them apart.
 */
fun buildDigestEmailBody(entries: List<DigestQueueEntry>, unsubscribeLink: String): String {
    val tenderLines = entries.joinToString("\n\n") { entry ->
        val tender = entry.tender
        "Tender: ${tender.title} | Issued by: ${tender.issuingAuthority} | " +
            "Deadline: ${tender.deadline ?: "n/a"} | Official source: ${tender.sourceUrl} | " +
            "Matched profile: ${entry.profile.name}"
    }
    return "Your TenderPulse daily digest — ${entries.size} matching tender(s):\n\n" +
        tenderLines +
        "\n\nUnsubscribe: $unsubscribeLink"
}
