package com.tenderpulse.auth

import com.tenderpulse.domain.SubscriberRepository
import org.springframework.beans.factory.annotation.Value
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
     */
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
     */
    @Transactional
    fun verify(rawToken: String): String {
        val record = tokenRepository.findByTokenHash(TokenHasher.hash(rawToken))
            ?: throw MagicLinkTokenNotFoundException()

        if (record.usedAt != null) throw MagicLinkTokenAlreadyUsedException()
        if (record.expiresAt.isBefore(Instant.now())) throw MagicLinkTokenExpiredException()

        tokenRepository.save(record.copy(usedAt = Instant.now()))
        return bearerTokenService.issue(record.subscriberId)
    }
}
