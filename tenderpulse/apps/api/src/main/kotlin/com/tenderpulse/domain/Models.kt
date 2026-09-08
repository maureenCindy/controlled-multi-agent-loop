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

/**
 * TP-121 (issue #121): the subscription plan a [Subscriber] holds, per
 * `tenderpulse/docs/specs/subscription-lifecycle-paypal.md` §6.1. Deliberately three explicit
 * values rather than a generic `PAID` value (the two-value model this replaced) -- `PAID` could
 * not distinguish Pro entitlements from Max entitlements, which the later phases of that spec's
 * milestone need to.
 *
 * Phase 1 (this task) treats [MAX] identically to [PRO] everywhere -- every tier-gated code path
 * (WhatsApp opt-in eligibility, [com.tenderpulse.notification.NotificationService] dispatch,
 * [com.tenderpulse.notification.ReminderService] dispatch) branches on "[FREE] vs. not [FREE]",
 * not on [PRO] vs [MAX] specifically. Max-exclusive behavior (the Market Insights Portal, uncapped
 * category matching) is explicitly out of scope until a later phase in that milestone.
 */
enum class SubscriptionPlan {
    FREE,   // Weekly digest, one category
    PRO,    // Real-time alerts, deadline reminders, WhatsApp, one category
    MAX     // Same delivery as PRO plus Market Insights Portal access (later phase) and unlimited categories (later phase)
}

/**
 * TP-121 (issue #121): TenderBell's view of a subscriber's PayPal billing state, per spec §6.2.
 * Not yet stored anywhere or wired into any behavior in this phase -- it exists so the
 * `billing_subscriptions` table (added by this same task, see the V11 Flyway migration) and later
 * phases (PayPal webhook/checkout handling) have the enum to build on.
 */
enum class BillingStatus {
    NONE,                 // Free or never entered PayPal checkout
    APPROVAL_PENDING,     // PayPal subscription created but not active
    ACTIVE,
    PAYMENT_FAILED,
    SUSPENDED,
    CANCELLATION_PENDING, // TenderBell is resolving cancellation state
    CANCELLED,
    EXPIRED
}

/**
 * TP-121 (issue #121): whether a subscriber's alert delivery is currently active, per spec §6.3.
 * Stored on [Subscriber.alertStatus] starting this phase, but not yet read by any alert-eligibility
 * check -- [com.tenderpulse.notification.NotificationService] and
 * [com.tenderpulse.notification.ReminderService] still gate solely on the pre-existing
 * [Subscriber.active] / [Subscriber.emailOptOut] flags (the "TP-041 consent guarantee"). Wiring
 * pause/resume/unsubscribe logic through this field is explicitly a later phase (self-service
 * preferences, spec §18 Phase 4), not this task.
 */
enum class AlertStatus {
    ACTIVE,
    PAUSED,
    UNSUBSCRIBED
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

    /**
     * TP-121 (issue #121): kept as a Kotlin property named `tier` for minimal blast radius (every
     * existing call site, DTO, and test that reads/writes `subscriber.tier` keeps compiling
     * unchanged), but the underlying **column** is `plan` per
     * `tenderpulse/docs/specs/subscription-lifecycle-paypal.md` §7.1 -- `@Column(name = "plan")`
     * below is what makes that true. The V10 migration renames the old `tier` column to `plan` and
     * migrates existing `PAID` rows to `PRO` (see that migration's header for the empirical
     * verification against a populated Postgres container).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "plan")
    val tier: SubscriptionPlan = SubscriptionPlan.FREE,

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
     * PayPal subscription ID for a PRO-tier signup (TP-042), stored only after the backend has
     * independently verified the subscription with PayPal's API (never trusted from the client).
     * Null for FREE subscribers and any subscriber that has never completed Pro checkout.
     *
     * `unique = true` (multiple NULLs still allowed) so the same PayPal subscription ID cannot be
     * linked to more than one Subscriber row — defence in depth alongside the explicit
     * check-before-save in [com.tenderpulse.subscriber.SubscriberService.registerPro], which is
     * what actually rejects a reused ID with a 400 rather than a raw constraint-violation 500.
     */
    @Column(unique = true)
    val paypalSubscriptionId: String? = null,

    /**
     * WhatsApp number opted in for notification delivery (TP-093), E.164-formatted (e.g.
     * `+263771234567`) — validated at the request level by
     * [com.tenderpulse.subscriber.WhatsAppOptInRequest], not re-validated here. Null until a
     * Paid subscriber submits one via `PATCH /api/v1/subscribers/{id}/whatsapp`. Sending any
     * actual WhatsApp message is out of scope here (TP-094) — this field only stores where a
     * future send would go.
     */
    val whatsappNumber: String? = null,

    /**
     * Self-attested WhatsApp delivery consent (TP-093): true only when the subscriber explicitly
     * sent `consentGiven: true` in the *same request* that submitted [whatsappNumber] — see
     * [com.tenderpulse.subscriber.SubscriberService.setWhatsAppOptIn]. Deliberately never
     * defaulted true merely because a number was entered, and deliberately not flipped by any
     * separate verification step (e.g. a `wa.me`-link click or inbound-webhook reply) — that kind
     * of verification is explicitly out of scope for this task. `@ColumnDefault` matters here for
     * the same reason it did for [InterestProfile.name] (see that field's kdoc): this app is now
     * Flyway-managed with `ddl-auto: validate` (TP-061), so the DB-level default is what lets the
     * corresponding migration (`V9__add_whatsapp_fields_to_subscribers.sql`) add this as a
     * `NOT NULL` column against an already-populated `subscribers` table without failing or
     * requiring a manual backfill — existing rows get `false`.
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    val whatsappOptIn: Boolean = false,

    /**
     * TP-121 (issue #121, spec §7.1/§6.3): whether this subscriber's alert delivery is currently
     * active. Added this phase as schema-only groundwork -- **not yet wired into any alert-
     * eligibility check** (see [AlertStatus]'s kdoc). `@ColumnDefault` mirrors [whatsappOptIn]'s
     * reasoning: the V10 migration adds this as a `NOT NULL` column against an already-populated
     * `subscribers` table, so every existing row needs a real default (`ACTIVE` is correct here --
     * every current subscriber's alerts are, in fact, active).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @ColumnDefault("'ACTIVE'")
    val alertStatus: AlertStatus = AlertStatus.ACTIVE,

    /**
     * TP-121 (issue #121, spec §7.1): whether alert emails may be delivered. Added this phase as
     * schema-only groundwork -- deliberately **not** used by any alert-dispatch code yet; the
     * pre-existing [emailOptOut] flag remains the sole enforced consent gate in this phase (see
     * `tenderpulse/docs/specs/privacy-note.md`'s "consent guarantee"). Defaults `true` on the same
     * backfill reasoning as [alertStatus]: every existing subscriber has been receiving email, so
     * `true` is the correct historical value, not just a placeholder.
     */
    @Column(nullable = false)
    @ColumnDefault("true")
    val emailEnabled: Boolean = true,

    /** TP-121 (issue #121, spec §7.1): evidence of opt-in/resubscription. Nullable -- schema-only this phase, not yet set by any code path. */
    val emailConsentAt: Instant? = null,

    /** TP-121 (issue #121, spec §7.1): optional automatic alert-resumption time. Nullable -- schema-only this phase, not yet set by any code path. */
    val alertsPausedUntil: Instant? = null,

    /**
     * TP-121 (issue #121, spec §7.1): audit-support timestamp for the last local change to this
     * row. `@ColumnDefault` follows the same populated-table backfill reasoning as [alertStatus] /
     * [emailEnabled] -- `CURRENT_TIMESTAMP` at migration time is a defensible "last known change"
     * value for pre-existing rows since no more precise historical value is recoverable.
     */
    @Column(nullable = false)
    @ColumnDefault("CURRENT_TIMESTAMP")
    val updatedAt: Instant = Instant.now()
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
