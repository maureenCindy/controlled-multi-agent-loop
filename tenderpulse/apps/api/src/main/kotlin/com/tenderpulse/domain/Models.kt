package com.tenderpulse.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class Sector {
    CONSTRUCTION, IT, HEALTHCARE, EDUCATION, TRANSPORT, ENERGY, AGRICULTURE, OTHER
}

enum class NotificationChannel {
    EMAIL, SMS, IN_APP
}

enum class SubscriptionTier {
    FREE,      // daily digest
    PAID       // real-time, advanced filters, history, analytics
}

@Entity
@Table(name = "tenders")
data class Tender(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val title: String,

    @Column(columnDefinition = "TEXT")
    val description: String? = null,

    @Enumerated(EnumType.STRING)
    val sector: Sector = Sector.OTHER,

    val valueMin: BigDecimal? = null,
    val valueMax: BigDecimal? = null,

    val issuingAuthority: String,

    val region: String? = null,

    val sourceUrl: String,

    val sourceName: String,

    val publishedAt: Instant = Instant.now(),

    val deadline: Instant? = null,

    val externalTenderId: String? = null,

    val currency: String? = null,

    @ElementCollection
    @CollectionTable(name = "tender_keywords")
    val keywords: MutableSet<String> = mutableSetOf(),

    val createdAt: Instant = Instant.now()
)

@Entity
@Table(name = "subscribers")
data class Subscriber(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false, unique = true)
    val email: String,

    val phone: String? = null,

    @Enumerated(EnumType.STRING)
    val tier: SubscriptionTier = SubscriptionTier.FREE,

    val active: Boolean = true,

    val createdAt: Instant = Instant.now(),

    /**
     * PayPal subscription ID for a PAID-tier signup (TP-042), stored only after the backend has
     * independently verified the subscription with PayPal's API (never trusted from the client).
     * Null for FREE subscribers and any subscriber that has never completed Pro checkout.
     */
    val paypalSubscriptionId: String? = null
)

@Entity
@Table(name = "interest_profiles")
data class InterestProfile(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscriber_id", nullable = false)
    val subscriber: Subscriber,

    @ElementCollection
    @CollectionTable(name = "profile_sectors")
    @Enumerated(EnumType.STRING)
    val sectors: MutableSet<Sector> = mutableSetOf(),

    val valueMin: BigDecimal? = null,
    val valueMax: BigDecimal? = null,

    val issuingAuthorityContains: String? = null,

    val region: String? = null,

    @ElementCollection
    @CollectionTable(name = "profile_keywords")
    val keywords: MutableSet<String> = mutableSetOf(),

    @ElementCollection
    @CollectionTable(name = "profile_channels")
    @Enumerated(EnumType.STRING)
    val preferredChannels: MutableSet<NotificationChannel> = mutableSetOf(NotificationChannel.EMAIL),

    val active: Boolean = true
)

@Entity
@Table(name = "notifications")
data class NotificationRecord(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscriber_id", nullable = false)
    val subscriber: Subscriber,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_id", nullable = false)
    val tender: Tender,

    @Enumerated(EnumType.STRING)
    val channel: NotificationChannel,

    val sentAt: Instant = Instant.now(),

    val success: Boolean = true,

    val errorMessage: String? = null
)

@Entity
@Table(name = "digest_queue_entries")
data class DigestQueueEntry(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscriber_id", nullable = false)
    val subscriber: Subscriber,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_id", nullable = false)
    val tender: Tender,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "profile_id", nullable = false)
    val profile: InterestProfile,

    val queuedAt: Instant = Instant.now(),

    val digestedAt: Instant? = null
)
