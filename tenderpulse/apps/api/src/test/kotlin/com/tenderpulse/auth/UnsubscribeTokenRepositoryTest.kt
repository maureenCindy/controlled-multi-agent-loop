package com.tenderpulse.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import java.util.UUID

/**
 * [UnsubscribeToken] save-read round trip against a real (H2) JPA context (TP-057) — same
 * reasoning as [com.tenderpulse.domain.EntityPersistenceTest] for the pre-existing entities: a
 * mocked repository never exercises Hibernate's actual row -> entity instantiation path.
 */
@DataJpaTest
class UnsubscribeTokenRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var tokenRepository: UnsubscribeTokenRepository

    @Test
    fun `UnsubscribeToken save-read round trip against a real JPA context`() {
        val subscriberId = UUID.randomUUID()
        val saved = tokenRepository.save(
            UnsubscribeToken(subscriberId = subscriberId, tokenHash = TokenHasher.hash("raw-token"))
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = tokenRepository.findById(saved.id).orElseThrow()

        assertEquals(subscriberId, reloaded.subscriberId)
        assertEquals(TokenHasher.hash("raw-token"), reloaded.tokenHash)
        assertNull(reloaded.usedAt)
        assertNotNull(reloaded.createdAt)
    }

    @Test
    fun `findByTokenHash finds the matching token`() {
        val subscriberId = UUID.randomUUID()
        tokenRepository.save(UnsubscribeToken(subscriberId = subscriberId, tokenHash = TokenHasher.hash("raw-token")))
        entityManager.flush()
        entityManager.clear()

        val found = tokenRepository.findByTokenHash(TokenHasher.hash("raw-token"))
        val notFound = tokenRepository.findByTokenHash(TokenHasher.hash("some-other-value"))

        assertEquals(subscriberId, found?.subscriberId)
        assertNull(notFound)
    }

    @Test
    fun `a subscriber can accumulate more than one token, one per email sent`() {
        val subscriberId = UUID.randomUUID()
        tokenRepository.save(UnsubscribeToken(subscriberId = subscriberId, tokenHash = TokenHasher.hash("raw-token-1")))
        tokenRepository.save(UnsubscribeToken(subscriberId = subscriberId, tokenHash = TokenHasher.hash("raw-token-2")))
        entityManager.flush()
        entityManager.clear()

        assertNotNull(tokenRepository.findByTokenHash(TokenHasher.hash("raw-token-1")))
        assertNotNull(tokenRepository.findByTokenHash(TokenHasher.hash("raw-token-2")))
    }
}
