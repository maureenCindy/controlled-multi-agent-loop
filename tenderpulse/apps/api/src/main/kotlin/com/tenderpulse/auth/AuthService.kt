package com.tenderpulse.auth

import com.tenderpulse.domain.SubscriberRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Business logic for magic-link authentication (TP-038): request a link, then exchange a valid
 * one for a bearer token. See [com.tenderpulse.api.AuthController] for the two endpoints that
 * front this.
 */
@Service
class AuthService(
    private val subscriberRepository: SubscriberRepository,
    private val tokenRepository: MagicLinkTokenRepository,
    private val mailSender: MagicLinkMailSender,
    private val bearerTokenService: BearerTokenService,
    @Value("\${tenderpulse.auth.magic-link-ttl-ms:86400000}") private val magicLinkTtlMs: Long,
    @Value("\${tenderpulse.auth.verify-base-url}") private val verifyBaseUrl: String
) {
    /**
     * Always returns normally (no result to leak): if [email] matches a [Subscriber][com.tenderpulse.domain.Subscriber],
     * a token is generated and emailed; if not, this is a no-op. Either way the caller
     * ([com.tenderpulse.api.AuthController]) returns the exact same response, so the API surface
     * carries no account-enumeration signal.
     *
     * `@Async` (TP-070/#70): closes a *timing* side-channel that survived the identical-response
     * fix above — a matched email used to do a DB write + synchronous SMTP send inline, vs. a
     * single SELECT for an unmatched one, which was measurably slower and so leaked the same
     * enumeration signal via latency instead of response content. Because this method returns
     * `Unit` (void), Spring's async proxy (enabled via `@EnableAsync` on
     * [com.tenderpulse.TenderPulseApplication]) submits the whole body — including the initial
     * `findByEmail` lookup — to a background thread and returns to the caller immediately,
     * without waiting for it. The caller ([com.tenderpulse.api.AuthController]) therefore always
     * responds before any of this method's work has necessarily even started, so matched and
     * unmatched requests are equally fast from the outside, regardless of what happens inside.
     */
    @Async
    fun requestMagicLink(email: String) {
        val subscriber = subscriberRepository.findByEmail(email) ?: return

        val rawToken = RawTokenGenerator.generate()
        tokenRepository.save(
            MagicLinkToken(
                subscriberId = subscriber.id,
                tokenHash = TokenHasher.hash(rawToken),
                expiresAt = Instant.now().plusMillis(magicLinkTtlMs)
            )
        )
        mailSender.sendMagicLink(subscriber, "$verifyBaseUrl?token=$rawToken")
    }

    /**
     * Validates a raw magic-link token (single-use, time-limited) and, if valid, returns a
     * bearer access token for the subscriber it was issued to.
     *
     * Single-use enforcement (TP-070/#70): the initial [MagicLinkTokenRepository.findByTokenHash]
     * read below is only used to fail fast with a specific, existing exception for the
     * already-known-invalid cases (not found / already used / expired at read time) — it is
     * *not* what makes single-use safe under concurrency. That guarantee comes entirely from
     * [MagicLinkTokenRepository.markUsed], a single atomic `UPDATE ... WHERE used_at IS NULL`
     * statement: if two requests race with the same still-unused token, both may pass the reads
     * above, but the database allows only one of the two `UPDATE`s to actually claim the row (see
     * the repository method's kdoc). The loser gets `claimed == 0` and fails with the same
     * [MagicLinkTokenAlreadyUsedException] as the already-used fast path — a clean, existing
     * error, not a crash or a double-issued token.
     */
    @Transactional
    fun verify(rawToken: String): String {
        val record = tokenRepository.findByTokenHash(TokenHasher.hash(rawToken))
            ?: throw MagicLinkTokenNotFoundException()

        if (record.usedAt != null) throw MagicLinkTokenAlreadyUsedException()
        if (record.expiresAt.isBefore(Instant.now())) throw MagicLinkTokenExpiredException()

        val claimed = tokenRepository.markUsed(record.id, Instant.now())
        if (claimed == 0) throw MagicLinkTokenAlreadyUsedException()

        return bearerTokenService.issue(record.subscriberId)
    }
}
