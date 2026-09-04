package com.tenderpulse.notification

import com.tenderpulse.auth.UnsubscribeService
import com.tenderpulse.domain.*
import com.tenderpulse.matching.MatchingService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Delivers alerts when a tender matches a subscriber profile.
 *
 * Free tier  → enqueued for the daily digest (sending handled by a future job; see TP-013)
 * Paid tier  → real-time per preferred channel
 *
 * TP-041 (consent guarantee): [profileRepository].findAllActiveWithSubscriber() only returns
 * [InterestProfile] rows joined to a real [Subscriber] row (the FK is non-null at the DB
 * level, see Models.kt). The only place a [Subscriber] is ever created is
 * `SubscriberService.register()` (called from `POST /api/v1/subscribers`), i.e. an explicit
 * opt-in. (The pre-launch Waitlist feature that predated `Subscriber` opt-in was retired in
 * TP-037 — see #38.) So there is no code path that emails an address that wasn't explicitly
 * registered as a subscriber.
 */
@Service
class NotificationService(
    private val profileRepository: InterestProfileRepository,
    private val notificationRecordRepository: NotificationRecordRepository,
    private val digestQueueEntryRepository: DigestQueueEntryRepository,
    private val matchingService: MatchingService,
    private val channels: List<NotificationChannelSender>
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun notifyMatchingSubscribers(tender: Tender): Int {
        val profiles = profileRepository.findAllActiveWithSubscriber()
        var sent = 0

        for (profile in profiles) {
            if (!matchingService.matches(tender, profile)) continue

            val subscriber = profile.subscriber

            when (subscriber.tier) {
                SubscriptionTier.FREE -> {
                    digestQueueEntryRepository.save(
                        DigestQueueEntry(
                            subscriber = subscriber,
                            tender = tender,
                            profile = profile
                        )
                    )
                }
                SubscriptionTier.PAID -> {
                    val channelsToUse = profile.preferredChannels.ifEmpty {
                        setOf(NotificationChannel.EMAIL)
                    }
                    for (channel in channelsToUse) {
                        val sender = channels.find { it.channel == channel }
                        if (sender == null) {
                            log.warn("No sender registered for channel {}", channel)
                            continue
                        }
                        val result = sender.send(subscriber, tender, profile)
                        notificationRecordRepository.save(
                            NotificationRecord(
                                subscriber = subscriber,
                                tender = tender,
                                channel = channel,
                                success = result.success,
                                errorMessage = result.error
                            )
                        )
                        if (result.success) sent++
                    }
                }
            }
        }
        return sent
    }
}

interface NotificationChannelSender {
    val channel: NotificationChannel
    fun send(subscriber: Subscriber, tender: Tender, profile: InterestProfile): SendResult
}

data class SendResult(val success: Boolean, val error: String? = null)

@Service
class EmailNotificationSender(
    private val unsubscribeService: UnsubscribeService
) : NotificationChannelSender {
    override val channel = NotificationChannel.EMAIL
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(subscriber: Subscriber, tender: Tender, profile: InterestProfile): SendResult {
        // Scaffold: log only. Wire JavaMailSender in production.
        val unsubscribeLink = unsubscribeService.buildUnsubscribeLink(subscriber)
        log.info("EMAIL → {} | {}", subscriber.email, buildAlertBody(tender, unsubscribeLink))
        return SendResult(success = true)
    }
}

/**
 * Builds the outbound alert content for a matched tender.
 *
 * TP-041: every alert (email now; digest content later, TP-013) must attribute the issuing
 * authority and link back to the official PRAZ e-GP listing rather than hosting the full
 * bid document ourselves — see tenderpulse/docs/specs/zw-tender-sources.md. Pulled out as a standalone function
 * (instead of inline string formatting inside [EmailNotificationSender.send]) so this
 * requirement is independently unit-testable.
 *
 * TP-057: also includes a working, no-login-required unsubscribe link
 * ([com.tenderpulse.auth.UnsubscribeService.buildUnsubscribeLink]) so every outbound email lets
 * the recipient opt out without contacting support.
 */
fun buildAlertBody(tender: Tender, unsubscribeLink: String): String =
    "Tender: ${tender.title} | Issued by: ${tender.issuingAuthority} | " +
        "Deadline: ${tender.deadline ?: "n/a"} | Official source: ${tender.sourceUrl} | " +
        "Unsubscribe: $unsubscribeLink"

@Service
class SmsNotificationSender : NotificationChannelSender {
    override val channel = NotificationChannel.SMS
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(subscriber: Subscriber, tender: Tender, profile: InterestProfile): SendResult {
        val phone = subscriber.phone
        if (phone.isNullOrBlank()) {
            return SendResult(success = false, error = "No phone number")
        }
        log.info("SMS → {} | {}", phone, tender.title)
        return SendResult(success = true)
    }
}

@Service
class InAppNotificationSender : NotificationChannelSender {
    override val channel = NotificationChannel.IN_APP
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(subscriber: Subscriber, tender: Tender, profile: InterestProfile): SendResult {
        log.info("IN_APP → subscriber {} | {}", subscriber.id, tender.title)
        return SendResult(success = true)
    }
}
