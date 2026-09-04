package com.tenderpulse.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface TenderRepository : JpaRepository<Tender, UUID> {
    fun findBySourceUrl(sourceUrl: String): Tender?
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

interface NotificationRecordRepository : JpaRepository<NotificationRecord, UUID>

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
}
