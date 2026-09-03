package com.tenderpulse.notification

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
class EmailNotificationSender : NotificationChannelSender {
    override val channel = NotificationChannel.EMAIL
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(subscriber: Subscriber, tender: Tender, profile: InterestProfile): SendResult {
        // Scaffold: log only. Wire JavaMailSender in production.
        log.info(
            "EMAIL → {} | Tender: {} | Deadline: {} | Link: {}",
            subscriber.email, tender.title, tender.deadline, tender.sourceUrl
        )
        return SendResult(success = true)
    }
}

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
