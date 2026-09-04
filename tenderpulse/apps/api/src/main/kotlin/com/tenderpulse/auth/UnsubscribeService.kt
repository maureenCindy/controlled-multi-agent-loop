package com.tenderpulse.auth

import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Unsubscribe / email preference management (TP-057, issue #57): lets a subscriber opt out of
 * future emails via a per-email, no-login-required link, without contacting support manually.
 *
 * Reuses the magic-link token pattern already established for subscriber auth
 * ([RawTokenGenerator], [TokenHasher] — only the SHA-256 hash of the raw, emailed token is ever
 * persisted, see [UnsubscribeToken]) for consistency, but this is a separate, single-purpose
 * token: it is looked up in its own table ([UnsubscribeTokenRepository]), never
 * [MagicLinkTokenRepository], so it can never be exchanged for a bearer access token
 * (see [AuthService.verify] / [BearerTokenService.issue]) — it can only ever flow into
 * [unsubscribe] below, which does nothing but flip [Subscriber.emailOptOut].
 */
@Service
class UnsubscribeService(
    private val subscriberRepository: SubscriberRepository,
    private val tokenRepository: UnsubscribeTokenRepository,
    @Value("\${tenderpulse.unsubscribe.base-url}") private val unsubscribeBaseUrl: String
) {

    /**
     * Mints a fresh single-email unsubscribe token for [subscriber] and returns the full link to
     * embed in an outbound email (see [com.tenderpulse.notification.EmailNotificationSender]).
     * A new token is minted per call/per email rather than reused, mirroring
     * [AuthService.requestMagicLink] — but unlike a magic-link token, this one is never
     * invalidated by use (see [UnsubscribeToken]), so an old email's link keeps working.
     */
    @Transactional
    fun buildUnsubscribeLink(subscriber: Subscriber): String {
        val rawToken = RawTokenGenerator.generate()
        tokenRepository.save(
            UnsubscribeToken(
                subscriberId = subscriber.id,
                tokenHash = TokenHasher.hash(rawToken)
            )
        )
        return "$unsubscribeBaseUrl?token=$rawToken"
    }

    /**
     * Opts the subscriber that issued [rawToken] out of future emails. No login required (called
     * straight off an unauthenticated `GET /api/v1/unsubscribe?token=...`).
     *
     * Idempotent by design: clicking the same (or any other) valid link more than once simply
     * re-confirms [Subscriber.emailOptOut] = true — no error, no state beyond that changes.
     *
     * @throws InvalidUnsubscribeTokenException if [rawToken] is unknown or tampered with (its
     *   hash matches no issued [UnsubscribeToken]) or its subscriber no longer exists — in either
     *   case nothing is changed.
     */
    @Transactional
    fun unsubscribe(rawToken: String) {
        val token = tokenRepository.findByTokenHash(TokenHasher.hash(rawToken))
            ?: throw InvalidUnsubscribeTokenException()

        val subscriber = subscriberRepository.findById(token.subscriberId).orElse(null)
            ?: throw InvalidUnsubscribeTokenException()

        if (!subscriber.emailOptOut) {
            subscriberRepository.save(subscriber.copy(emailOptOut = true))
        }

        if (token.usedAt == null) {
            tokenRepository.save(token.copy(usedAt = Instant.now()))
        }
    }
}

/** Response body for `GET /api/v1/unsubscribe` — deliberately the same shape whether or not the
 * link had already been used, since re-clicking it is a no-op, not an error (see
 * [UnsubscribeService.unsubscribe]). */
data class UnsubscribeResponse(
    val message: String = "You have been unsubscribed from TenderPulse emails."
)
