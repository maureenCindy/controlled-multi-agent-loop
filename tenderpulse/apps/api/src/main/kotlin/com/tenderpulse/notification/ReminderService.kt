package com.tenderpulse.notification

import com.tenderpulse.domain.DeadlineReminderRecord
import com.tenderpulse.domain.DeadlineReminderRecordRepository
import com.tenderpulse.domain.DigestQueueEntry
import com.tenderpulse.domain.DigestQueueEntryRepository
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.NotificationRecordRepository
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.domain.Tender
import com.tenderpulse.domain.TenderRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

/**
 * Sends a follow-up "deadline approaching" reminder for a tender a subscriber was already told
 * about (TP-056, issue #56) — a second nudge on top of the one-time initial match alert sent by
 * [NotificationService], not a new discovery mechanism.
 *
 * Mirrors [DigestService] / [com.tenderpulse.aggregation.AggregationService]'s scheduled-job
 * structure and disabled-by-default config pattern.
 *
 * "Previously notified of the match" is determined by looking at the two existing notification
 * history tables per tender: [NotificationRecordRepository] (successful Paid-tier immediate
 * sends, TP-012) and [DigestQueueEntryRepository] (Free-tier digest-queue entries, TP-012/TP-013)
 * — never [InterestProfileRepository] directly, so a subscriber whose profile matches a tender
 * but who was never actually notified of it (e.g. the notify step failed, or the match hasn't
 * been processed yet) is correctly excluded (AC: "a subscriber never notified of the original
 * match does not receive a reminder").
 *
 * [runReminderCycle] is deliberately **not** wrapped in one class-level `@Transactional`, mirroring
 * [DigestService]'s reasoning: each (tender, subscriber) pair is processed and persisted
 * independently (via each repository call's own per-call transaction) so a failure handling one
 * pair can never mark a single shared transaction rollback-only and silently undo another,
 * already-successfully-reminded pair's [DeadlineReminderRecord] at commit time.
 */
@Service
class ReminderService(
    private val tenderRepository: TenderRepository,
    private val notificationRecordRepository: NotificationRecordRepository,
    private val digestQueueEntryRepository: DigestQueueEntryRepository,
    private val deadlineReminderRecordRepository: DeadlineReminderRecordRepository,
    private val profileRepository: InterestProfileRepository,
    private val emailNotificationSender: EmailNotificationSender,
    @Value("\${tenderpulse.reminder.window-days:3}")
    private val windowDays: Long,
    @Value("\${tenderpulse.reminder.scheduled-enabled:false}")
    private val scheduledEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun runReminderCycle(): ReminderResult {
        val now = Instant.now()
        val windowEnd = now.plus(Duration.ofDays(windowDays))
        val upcomingTenders = tenderRepository.findByDeadlineBetween(now, windowEnd)

        var remindersSent = 0
        var digestEntriesQueued = 0
        var failed = 0

        for (tender in upcomingTenders) {
            val (sent, queued, failures) = remindPreviouslyNotifiedSubscribers(tender)
            remindersSent += sent
            digestEntriesQueued += queued
            failed += failures
        }

        return ReminderResult(
            remindersSent = remindersSent,
            digestEntriesQueued = digestEntriesQueued,
            failed = failed
        )
    }

    private fun remindPreviouslyNotifiedSubscribers(tender: Tender): Triple<Int, Int, Int> {
        // Paid subscribers previously notified via an immediate, successful send.
        val paidNotifiedSubscribers = notificationRecordRepository
            .findByTenderIdAndSuccessTrue(tender.id)
            .map { it.subscriber }
            .distinctBy { it.id }

        // Free subscribers previously notified via a digest-queue entry (queuing itself is the
        // "notified" event for Free tier — see class doc).
        val freeQueueEntriesBySubscriberId = digestQueueEntryRepository
            .findByTenderId(tender.id)
            .distinctBy { it.subscriber.id }
            .associateBy { it.subscriber.id }

        // NotificationRecord rows only ever exist for PAID sends and DigestQueueEntry rows only
        // for FREE queues (see NotificationService.notifyMatchingSubscribers), so in practice
        // these two sets are disjoint by subscriber id — union defensively rather than assume it.
        val candidateSubscribers = (
            paidNotifiedSubscribers.associateBy { it.id } +
                freeQueueEntriesBySubscriberId.mapValues { it.value.subscriber }
            ).values

        var sent = 0
        var queued = 0
        var failed = 0

        for (subscriber in candidateSubscribers) {
            try {
                if (deadlineReminderRecordRepository.existsBySubscriberIdAndTenderId(subscriber.id, tender.id)) {
                    continue
                }

                when (subscriber.tier) {
                    SubscriptionTier.PAID -> {
                        val profile = freeQueueEntriesBySubscriberId[subscriber.id]?.profile
                            ?: profileRepository.findBySubscriberIdAndActiveTrue(subscriber.id).firstOrNull()
                        if (profile == null) {
                            log.warn(
                                "No active interest profile for subscriber {}; skipping deadline reminder for tender {}",
                                subscriber.id,
                                tender.id
                            )
                            continue
                        }
                        val result = emailNotificationSender.send(subscriber, tender, profile)
                        if (result.success) {
                            deadlineReminderRecordRepository.save(
                                DeadlineReminderRecord(subscriber = subscriber, tender = tender)
                            )
                            sent++
                        } else {
                            failed++
                            log.warn(
                                "Deadline reminder email failed for subscriber {} tender {}: {}",
                                subscriber.id,
                                tender.id,
                                result.error
                            )
                        }
                    }
                    SubscriptionTier.FREE -> {
                        val profile = freeQueueEntriesBySubscriberId[subscriber.id]?.profile
                            ?: profileRepository.findBySubscriberIdAndActiveTrue(subscriber.id).firstOrNull()
                        if (profile == null) {
                            log.warn(
                                "No active interest profile for subscriber {}; skipping deadline reminder for tender {}",
                                subscriber.id,
                                tender.id
                            )
                            continue
                        }
                        digestQueueEntryRepository.save(
                            DigestQueueEntry(subscriber = subscriber, tender = tender, profile = profile)
                        )
                        deadlineReminderRecordRepository.save(
                            DeadlineReminderRecord(subscriber = subscriber, tender = tender)
                        )
                        queued++
                    }
                }
            } catch (e: Exception) {
                failed++
                log.error(
                    "Deadline reminder cycle failed for subscriber {} tender {}: {}",
                    subscriber.id,
                    tender.id,
                    e.message,
                    e
                )
            }
        }

        return Triple(sent, queued, failed)
    }

    /**
     * Scheduled deadline-reminder cycle (disabled by default).
     * Enable with: tenderpulse.reminder.scheduled-enabled=true
     * Configure the reminder window with: tenderpulse.reminder.window-days (default: 3)
     */
    @Scheduled(
        initialDelayString = "\${tenderpulse.reminder.scheduled-initial-delay-ms:0}",
        fixedDelayString = "\${tenderpulse.reminder.scheduled-interval-ms:86400000}"
    )
    fun scheduledReminderCycle() {
        if (!scheduledEnabled) {
            return
        }
        log.info("Running scheduled deadline reminder cycle")
        val result = runReminderCycle()
        log.info(
            "Scheduled deadline reminder cycle completed: remindersSent={}, digestEntriesQueued={}, failed={}",
            result.remindersSent,
            result.digestEntriesQueued,
            result.failed
        )
    }
}

data class ReminderResult(
    val remindersSent: Int,
    val digestEntriesQueued: Int,
    val failed: Int
)
