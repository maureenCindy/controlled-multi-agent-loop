package com.tenderpulse.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * A single magic-link token (TP-038). Only the SHA-256 hash of the raw, emailed token is
 * persisted (never the raw value itself) — this matches the "don't store the secret" pattern
 * used for e.g. password reset tokens, so a DB read alone can never be replayed as a login.
 *
 * Single-use is enforced via [usedAt]: null until [AuthService.verify] consumes it, after which
 * any further verify attempt with the same token is rejected. Expiry is enforced via
 * [expiresAt], set at issue time from `tenderpulse.auth.magic-link-ttl-ms` (default 24h — see
 * issue #39 "Assumptions").
 */
@Entity
@Table(name = "magic_link_tokens")
data class MagicLinkToken(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val subscriberId: UUID,

    @Column(nullable = false, unique = true)
    val tokenHash: String,

    @Column(nullable = false)
    val expiresAt: Instant,

    val usedAt: Instant? = null,

    val createdAt: Instant = Instant.now()
)

interface MagicLinkTokenRepository : JpaRepository<MagicLinkToken, UUID> {
    fun findByTokenHash(tokenHash: String): MagicLinkToken?

    /**
     * Atomically claims a token for single use (TP-070/#70): sets [MagicLinkToken.usedAt] to
     * [now] only if it is still null, in one conditional `UPDATE`, and returns the number of
     * rows affected (0 or 1, [id] being a primary key). A plain find-then-check-then-save from
     * the caller's side has a TOCTOU window between the read and the write where two concurrent
     * callers can both observe "not used yet"; a single `UPDATE ... WHERE used_at IS NULL`
     * doesn't have that window — the database guarantees only one concurrent transaction can win
     * the row lock and see its own `WHERE` clause still match, so at most one caller ever gets
     * `1` back for a given token. `@Transactional` here (rather than relying solely on the
     * caller, e.g. [AuthService.verify]'s own `@Transactional`) makes this method safe to call
     * standalone too, joining the caller's transaction if there is one (default `REQUIRED`
     * propagation) or opening its own otherwise.
     */
    @Modifying
    @Transactional
    @Query("update MagicLinkToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    fun markUsed(@Param("id") id: UUID, @Param("now") now: Instant): Int
}

/**
 * A single unsubscribe token (TP-057), embedded as a link in an outbound email — see
 * [UnsubscribeService.buildUnsubscribeLink]. Reuses the [MagicLinkToken] pattern (only the
 * SHA-256 hash of the raw, emailed token is persisted — see [TokenHasher]) for consistency, but
 * is deliberately its own table/lookup path so it can never be exchanged for a bearer access
 * token the way a magic-link token can: [UnsubscribeService.unsubscribe] only ever looks it up
 * here and flips [com.tenderpulse.domain.Subscriber.emailOptOut], nothing else.
 *
 * Unlike [MagicLinkToken] this is deliberately reusable, not single-use: a fresh token is minted
 * for every email (so [subscriberId] is intentionally NOT unique — a subscriber accumulates one
 * row per email sent to them), but clicking any one of them — including clicking the same link
 * more than once — must be a harmless no-op (TP-057 AC: idempotent unsubscribe), not an error.
 * [usedAt] is therefore purely informational (first-click timestamp), never checked to reject a
 * repeat click.
 */
@Entity
@Table(name = "unsubscribe_tokens")
data class UnsubscribeToken(
    @Id
    val id: UUID = UUID.randomUUID(),

    @Column(nullable = false)
    val subscriberId: UUID,

    @Column(nullable = false, unique = true)
    val tokenHash: String,

    val usedAt: Instant? = null,

    val createdAt: Instant = Instant.now()
)

interface UnsubscribeTokenRepository : JpaRepository<UnsubscribeToken, UUID> {
    fun findByTokenHash(tokenHash: String): UnsubscribeToken?
}
