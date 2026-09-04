package com.tenderpulse.domain

import jakarta.persistence.*
import org.hibernate.annotations.ColumnDefault
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

    /**
     * True once the subscriber has clicked the unsubscribe link embedded in an outbound email
     * (TP-057) — see [com.tenderpulse.auth.UnsubscribeService]. Deliberately a separate field
     * from [active]: [active] governs tier/account status elsewhere (e.g.
     * [com.tenderpulse.admin.AdminService]), and conflating "opted out of email" with "account
     * active" would let one flag silently mean two different things. Checked by
     * [InterestProfileRepository.findAllActiveWithSubscriber] so an opted-out subscriber is
     * excluded from matching/notification cycles going forward without needing to touch [active].
     */
    val emailOptOut: Boolean = false,

    val createdAt: Instant = Instant.now(),

    /**
     * PayPal subscription ID for a PAID-tier signup (TP-042), stored only after the backend has
     * independently verified the subscription with PayPal's API (never trusted from the client).
     * Null for FREE subscribers and any subscriber that has never completed Pro checkout.
     *
     * `unique = true` (multiple NULLs still allowed) so the same PayPal subscription ID cannot be
     * linked to more than one Subscriber row — defence in depth alongside the explicit
     * check-before-save in [com.tenderpulse.subscriber.SubscriberService.registerPro], which is
     * what actually rejects a reused ID with a 400 rather than a raw constraint-violation 500.
     */
    @Column(unique = true)
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

    /**
     * Subscriber-chosen label distinguishing this profile from any others they maintain (issue
     * #58: a subscriber may keep more than one named interest profile — matching and notification
     * already iterate every active profile independently, so this is the field that lets an alert
     * be attributed back to *which* profile triggered it). Required at both the entity and
     * [com.tenderpulse.subscriber.ProfileRequest] level (`@NotBlank`), so every profile — including
     * a subscriber's only one — has a meaningful label from creation.
     *
     * `@ColumnDefault` matters beyond documentation here: this app runs with `ddl-auto: update`
     * (no Flyway yet — see #49) against a real, already-populated Postgres table in any
     * environment that predates this field. Without a DB-level default, Hibernate emits
     * `ALTER TABLE ... ADD COLUMN name varchar(255) NOT NULL` with no way to backfill existing
     * rows, which Postgres rejects once any row already exists. With the default, the emitted DDL
     * becomes `... ADD COLUMN name varchar(255) NOT NULL DEFAULT 'Unnamed Profile'`, which Postgres
     * applies even to a populated table, backfilling existing rows with the default value.
     */
    @Column(nullable = false)
    @ColumnDefault("'Unnamed Profile'")
    val name: String,

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

/**
 * TP-056 (issue #56): channel-agnostic tracking record proving "was a deadline reminder already
 * sent for this (subscriber, tender) pair" — independent of whether the reminder ultimately went
 * out as an immediate Paid-tier email ([com.tenderpulse.notification.EmailNotificationSender]) or
 * a queued Free-tier [DigestQueueEntry]. A unique constraint on (subscriber, tender) is the
 * actual enforcement mechanism for "no duplicate reminder across multiple job runs" — the
 * application-level existence check in
 * [com.tenderpulse.notification.ReminderService.runReminderCycle] is what makes that guarantee
 * observable/testable without relying solely on a DB constraint violation.
 */
@Entity
@Table(
    name = "deadline_reminder_records",
    uniqueConstraints = [UniqueConstraint(columnNames = ["subscriber_id", "tender_id"])]
)
data class DeadlineReminderRecord(
    @Id
    val id: UUID = UUID.randomUUID(),

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subscriber_id", nullable = false)
    val subscriber: Subscriber,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tender_id", nullable = false)
    val tender: Tender,

    val sentAt: Instant = Instant.now()
)
