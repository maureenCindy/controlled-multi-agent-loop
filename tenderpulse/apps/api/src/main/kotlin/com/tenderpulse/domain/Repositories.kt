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

    @Query("SELECT p FROM InterestProfile p JOIN FETCH p.subscriber WHERE p.active = true AND p.subscriber.active = true")
    fun findAllActiveWithSubscriber(): List<InterestProfile>
}

interface NotificationRecordRepository : JpaRepository<NotificationRecord, UUID>

interface DigestQueueEntryRepository : JpaRepository<DigestQueueEntry, UUID> {
    fun findBySubscriberIdAndDigestedAtIsNull(subscriberId: UUID): List<DigestQueueEntry>
}
