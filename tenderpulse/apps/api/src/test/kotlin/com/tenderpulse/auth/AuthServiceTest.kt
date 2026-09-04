package com.tenderpulse.auth

import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for [AuthService] (TP-038). Repositories, mail sender and bearer-token issuance are
 * all mockk mocks — no live email sending, no DB, matching the "no live network in tests"
 * principle already used for the PRAZ adapter (see CONTRIBUTING.md).
 */
class AuthServiceTest {

    private val subscriberRepository = mockk<SubscriberRepository>()
    private val tokenRepository = mockk<MagicLinkTokenRepository>()
    private val mailSender = mockk<MagicLinkMailSender>(relaxed = true)
    private val bearerTokenService = mockk<BearerTokenService>()

    private val service = AuthService(
        subscriberRepository = subscriberRepository,
        tokenRepository = tokenRepository,
        mailSender = mailSender,
        bearerTokenService = bearerTokenService,
        magicLinkTtlMs = 86_400_000,
        verifyBaseUrl = "https://api.tenderpulse.example/api/v1/auth/verify"
    )

    private val subscriberId = UUID.randomUUID()
    private val subscriber = Subscriber(id = subscriberId, email = "sub@example.com")

    // ---- requestMagicLink ----

    @Test
    fun `requestMagicLink for an existing email saves a token and sends an email`() {
        every { subscriberRepository.findByEmail("sub@example.com") } returns subscriber
        val saved = slot<MagicLinkToken>()
        every { tokenRepository.save(capture(saved)) } answers { saved.captured }

        service.requestMagicLink("sub@example.com")

        assertEquals(subscriberId, saved.captured.subscriberId)
        assertNotNull(saved.captured.tokenHash)
        verify(exactly = 1) { mailSender.sendMagicLink(subscriber, match { it.startsWith("https://api.tenderpulse.example/api/v1/auth/verify?token=") }) }
    }

    @Test
    fun `requestMagicLink for a non-existent email saves no token and sends no email`() {
        every { subscriberRepository.findByEmail("nobody@example.com") } returns null

        service.requestMagicLink("nobody@example.com")

        verify(exactly = 0) { tokenRepository.save(any()) }
        verify(exactly = 0) { mailSender.sendMagicLink(any(), any()) }
    }

    @Test
    fun `each request generates a distinct raw token`() {
        every { subscriberRepository.findByEmail("sub@example.com") } returns subscriber
        val saved = mutableListOf<MagicLinkToken>()
        every { tokenRepository.save(capture(saved)) } answers { saved.last() }

        service.requestMagicLink("sub@example.com")
        service.requestMagicLink("sub@example.com")

        assertEquals(2, saved.size)
        assert(saved[0].tokenHash != saved[1].tokenHash)
    }

    // ---- verify ----

    @Test
    fun `verify with a valid unused unexpired token returns a bearer token and marks it used`() {
        val record = MagicLinkToken(
            subscriberId = subscriberId,
            tokenHash = TokenHasher.hash("raw-token"),
            expiresAt = Instant.now().plusSeconds(3600)
        )
        every { tokenRepository.findByTokenHash(TokenHasher.hash("raw-token")) } returns record
        // TP-070: single-use is now enforced via an atomic conditional UPDATE (see
        // MagicLinkTokenRepository.markUsed) rather than a plain save() of the mutated entity —
        // 1 row affected means this call is the one that claimed the token.
        every { tokenRepository.markUsed(record.id, any()) } returns 1
        // BearerTokenService.issue has a defaulted `now` param, evaluated fresh at each call
        // site — match it with any() rather than eq() so the stub isn't pinned to the exact
        // Instant captured when the `every {}` block itself ran.
        every { bearerTokenService.issue(subscriberId, any()) } returns "bearer-token-value"

        val result = service.verify("raw-token")

        assertEquals("bearer-token-value", result)
        verify(exactly = 1) { tokenRepository.markUsed(record.id, any()) }
    }

    @Test
    fun `verify with an unknown token throws MagicLinkTokenNotFoundException`() {
        every { tokenRepository.findByTokenHash(any()) } returns null

        assertThrows(MagicLinkTokenNotFoundException::class.java) {
            service.verify("unknown-token")
        }
        verify(exactly = 0) { bearerTokenService.issue(any(), any()) }
    }

    @Test
    fun `verify with an expired token throws MagicLinkTokenExpiredException`() {
        val record = MagicLinkToken(
            subscriberId = subscriberId,
            tokenHash = TokenHasher.hash("raw-token"),
            expiresAt = Instant.now().minusSeconds(60)
        )
        every { tokenRepository.findByTokenHash(any()) } returns record

        assertThrows(MagicLinkTokenExpiredException::class.java) {
            service.verify("raw-token")
        }
        verify(exactly = 0) { tokenRepository.markUsed(any(), any()) }
        verify(exactly = 0) { bearerTokenService.issue(any(), any()) }
    }

    @Test
    fun `verify with an already-used token throws MagicLinkTokenAlreadyUsedException`() {
        val record = MagicLinkToken(
            subscriberId = subscriberId,
            tokenHash = TokenHasher.hash("raw-token"),
            expiresAt = Instant.now().plusSeconds(3600),
            usedAt = Instant.now().minusSeconds(60)
        )
        every { tokenRepository.findByTokenHash(any()) } returns record

        assertThrows(MagicLinkTokenAlreadyUsedException::class.java) {
            service.verify("raw-token")
        }
        verify(exactly = 0) { tokenRepository.markUsed(any(), any()) }
        verify(exactly = 0) { bearerTokenService.issue(any(), any()) }
    }

    // ---- TP-070: TOCTOU fix ----

    @Test
    fun `verify with a token consumed by a concurrent request between the read and the atomic claim throws MagicLinkTokenAlreadyUsedException`() {
        // Simulates the race #70 closes: the read below still sees usedAt == null (this request
        // "lost" the race but hasn't found out yet), but by the time the atomic markUsed()
        // conditional UPDATE runs, another request has already claimed the row — 0 rows
        // affected. This is the codepath that makes verify() safe under concurrency; see
        // AuthServiceConcurrencyTest for the equivalent test against a real, racing DB.
        val record = MagicLinkToken(
            subscriberId = subscriberId,
            tokenHash = TokenHasher.hash("raw-token"),
            expiresAt = Instant.now().plusSeconds(3600)
        )
        every { tokenRepository.findByTokenHash(any()) } returns record
        every { tokenRepository.markUsed(record.id, any()) } returns 0

        assertThrows(MagicLinkTokenAlreadyUsedException::class.java) {
            service.verify("raw-token")
        }
        verify(exactly = 0) { bearerTokenService.issue(any(), any()) }
    }
}
