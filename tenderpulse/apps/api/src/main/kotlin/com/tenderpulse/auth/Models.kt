package com.tenderpulse.auth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
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
}
