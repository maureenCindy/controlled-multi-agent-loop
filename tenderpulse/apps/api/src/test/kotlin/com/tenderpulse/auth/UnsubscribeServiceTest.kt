package com.tenderpulse.auth

import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for [UnsubscribeService] (TP-057, issue #57). Repositories are mockk mocks — no
 * live DB, matching the "no live network" principle already used elsewhere (see
 * [AuthServiceTest]).
 */
class UnsubscribeServiceTest {

    private val subscriberRepository = mockk<SubscriberRepository>()
    private val tokenRepository = mockk<UnsubscribeTokenRepository>()

    private val service = UnsubscribeService(
        subscriberRepository = subscriberRepository,
        tokenRepository = tokenRepository,
        unsubscribeBaseUrl = "https://api.tenderpulse.example/api/v1/unsubscribe"
    )

    private val subscriberId = UUID.randomUUID()
    private val subscriber = Subscriber(id = subscriberId, email = "sub@example.com")

    // ---- buildUnsubscribeLink ----

    @Test
    fun `buildUnsubscribeLink saves a token for the subscriber and returns a working link`() {
        val saved = slot<UnsubscribeToken>()
        every { tokenRepository.save(capture(saved)) } answers { saved.captured }

        val link = service.buildUnsubscribeLink(subscriber)

        assertEquals(subscriberId, saved.captured.subscriberId)
        assertNotNull(saved.captured.tokenHash)
        assertTrue(link.startsWith("https://api.tenderpulse.example/api/v1/unsubscribe?token="))
    }

    @Test
    fun `each call mints a distinct raw token`() {
        val saved = mutableListOf<UnsubscribeToken>()
        every { tokenRepository.save(capture(saved)) } answers { saved.last() }

        val link1 = service.buildUnsubscribeLink(subscriber)
        val link2 = service.buildUnsubscribeLink(subscriber)

        assertEquals(2, saved.size)
        assert(saved[0].tokenHash != saved[1].tokenHash)
        assert(link1 != link2)
    }

    // ---- unsubscribe ----

    @Test
    fun `unsubscribe with a valid token opts the subscriber out and marks the token used`() {
        val token = UnsubscribeToken(
            subscriberId = subscriberId,
            tokenHash = TokenHasher.hash("raw-token")
        )
        every { tokenRepository.findByTokenHash(TokenHasher.hash("raw-token")) } returns token
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val savedSubscriber = slot<Subscriber>()
        every { subscriberRepository.save(capture(savedSubscriber)) } answers { savedSubscriber.captured }
        val savedToken = slot<UnsubscribeToken>()
        every { tokenRepository.save(capture(savedToken)) } answers { savedToken.captured }

        service.unsubscribe("raw-token")

        assertTrue(savedSubscriber.captured.emailOptOut)
        assertNotNull(savedToken.captured.usedAt)
    }

    @Test
    fun `unsubscribe with an unknown token throws InvalidUnsubscribeTokenException and changes nothing`() {
        every { tokenRepository.findByTokenHash(any()) } returns null

        assertThrows(InvalidUnsubscribeTokenException::class.java) {
            service.unsubscribe("unknown-token")
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
        verify(exactly = 0) { subscriberRepository.findById(any()) }
    }

    /** TP-057 AC/test case: a tampered token (never issued) is rejected, no state change. */
    @Test
    fun `unsubscribe with a tampered token throws InvalidUnsubscribeTokenException and changes nothing`() {
        // A genuine token exists for a *different* raw value; the tampered one hashes differently
        // and therefore matches nothing.
        every { tokenRepository.findByTokenHash(TokenHasher.hash("tampered-token")) } returns null

        assertThrows(InvalidUnsubscribeTokenException::class.java) {
            service.unsubscribe("tampered-token")
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /** TP-057 AC/test case: reusing an already-used unsubscribe link is idempotent, no error. */
    @Test
    fun `unsubscribe with an already-used token is idempotent and does not error`() {
        val alreadyOptedOut = subscriber.copy(emailOptOut = true)
        val token = UnsubscribeToken(
            subscriberId = subscriberId,
            tokenHash = TokenHasher.hash("raw-token"),
            usedAt = java.time.Instant.now().minusSeconds(60)
        )
        every { tokenRepository.findByTokenHash(TokenHasher.hash("raw-token")) } returns token
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(alreadyOptedOut)

        service.unsubscribe("raw-token")

        // Already opted out and already used: nothing further needs to be written.
        verify(exactly = 0) { subscriberRepository.save(any()) }
        verify(exactly = 0) { tokenRepository.save(any()) }
    }

    @Test
    fun `unsubscribe with a token whose subscriber no longer exists throws and changes nothing`() {
        val token = UnsubscribeToken(
            subscriberId = subscriberId,
            tokenHash = TokenHasher.hash("raw-token")
        )
        every { tokenRepository.findByTokenHash(TokenHasher.hash("raw-token")) } returns token
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        assertThrows(InvalidUnsubscribeTokenException::class.java) {
            service.unsubscribe("raw-token")
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
        verify(exactly = 0) { tokenRepository.save(any()) }
    }

    @Test
    fun `unsubscribe leaves the subscriber tier and active flag untouched`() {
        val token = UnsubscribeToken(subscriberId = subscriberId, tokenHash = TokenHasher.hash("raw-token"))
        every { tokenRepository.findByTokenHash(TokenHasher.hash("raw-token")) } returns token
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val savedSubscriber = slot<Subscriber>()
        every { subscriberRepository.save(capture(savedSubscriber)) } answers { savedSubscriber.captured }
        every { tokenRepository.save(any()) } answers { it.invocation.args[0] as UnsubscribeToken }

        service.unsubscribe("raw-token")

        assertEquals(subscriber.tier, savedSubscriber.captured.tier)
        assertTrue(savedSubscriber.captured.active)
        assertFalse(subscriber.emailOptOut) // original object is unchanged (Subscriber is a data class copy)
    }
}
