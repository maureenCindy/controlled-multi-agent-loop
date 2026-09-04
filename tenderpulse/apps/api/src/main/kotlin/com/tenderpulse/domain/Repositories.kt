package com.tenderpulse.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.UUID

interface TenderRepository : JpaRepository<Tender, UUID> {
    fun findBySourceUrl(sourceUrl: String): Tender?

    /**
     * TP-056: tenders whose deadline falls within [from, to] inclusive — used by
     * [com.tenderpulse.notification.ReminderService] to find tenders whose deadline is within the
     * reminder window and has not yet passed. Callers pass `from = Instant.now()` so a tender
     * whose deadline already passed (deadline < now) is never returned, and `to = now + window`
     * so a tender too far in the future is excluded too.
     */
    fun findByDeadlineBetween(from: Instant, to: Instant): List<Tender>
}

interface SubscriberRepository : JpaRepository<Subscriber, UUID> {
    fun findByEmail(email: String): Subscriber?
    fun findByActiveTrue(): List<Subscriber>

    /**
     * Used by [com.tenderpulse.subscriber.SubscriberService.registerPro] (TP-042) to reject
     * reusing the same PayPal subscription ID to upgrade more than one Subscriber record.
     */
    fun findByPaypalSubscriptionId(paypalSubscriptionId: String): Subscriber?
}

interface InterestProfileRepository : JpaRepository<InterestProfile, UUID> {
    fun findBySubscriberIdAndActiveTrue(subscriberId: UUID): List<InterestProfile>
    fun findBySubscriberId(subscriberId: UUID): List<InterestProfile>

    /**
     * TP-057: also excludes subscribers who have clicked the unsubscribe link
     * ([Subscriber.emailOptOut]), on top of the pre-existing active-profile/active-subscriber
     * filters, so opted-out subscribers are excluded from matching/notification cycles going
     * forward without any further caller-side filtering.
     */
    @Query(
        "SELECT p FROM InterestProfile p JOIN FETCH p.subscriber " +
            "WHERE p.active = true AND p.subscriber.active = true AND p.subscriber.emailOptOut = false"
    )
    fun findAllActiveWithSubscriber(): List<InterestProfile>
}

interface NotificationRecordRepository : JpaRepository<NotificationRecord, UUID> {
    /**
     * TP-056: every successful Paid-tier send for a given tender — used by
     * [com.tenderpulse.notification.ReminderService] to find which Paid subscribers were
     * previously (successfully) notified of this tender's original match, and are therefore
     * eligible for a deadline reminder. Filtered to `success = true` deliberately: a failed
     * original send never actually reached the subscriber, so it shouldn't count as "already
     * told about this tender" for reminder-eligibility purposes.
     */
    fun findByTenderIdAndSuccessTrue(tenderId: UUID): List<NotificationRecord>
}

interface DigestQueueEntryRepository : JpaRepository<DigestQueueEntry, UUID> {
    fun findBySubscriberIdAndDigestedAtIsNull(subscriberId: UUID): List<DigestQueueEntry>

    /**
     * TP-013: every pending (undigested) entry across every subscriber, used by
     * [com.tenderpulse.notification.DigestService] to find which Free-tier subscribers have at
     * least one matched tender to include in today's daily digest. Grouped by subscriber in
     * application code (DigestService) rather than a dedicated distinct-subscriber-ids query,
     * given the expected low volume of undigested entries at this stage of the product.
     */
    fun findAllByDigestedAtIsNull(): List<DigestQueueEntry>

    /**
     * TP-056: every digest-queue entry (digested or not) for a given tender — used by
     * [com.tenderpulse.notification.ReminderService] to find which Free-tier subscribers were
     * previously notified of this tender's original match (queuing itself, not the eventual
     * digest send, is what "notified" means for the Free tier per TP-012/TP-013), and are
     * therefore eligible for a deadline reminder.
     */
    fun findByTenderId(tenderId: UUID): List<DigestQueueEntry>
}

interface DeadlineReminderRecordRepository : JpaRepository<DeadlineReminderRecord, UUID> {
    /**
     * TP-056: the sole guard against sending more than one deadline reminder for the same
     * (subscriber, tender) pair across multiple [com.tenderpulse.notification.ReminderService]
     * job runs.
     */
    fun existsBySubscriberIdAndTenderId(subscriberId: UUID, tenderId: UUID): Boolean
}
