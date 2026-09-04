package com.tenderpulse.domain

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import org.springframework.dao.DataIntegrityViolationException

/**
 * Proves the DB-level uniqueness constraints referenced by #64 actually fire (a real
 * [DataIntegrityViolationException] from a real, H2-backed JPA context) — this is the failure
 * mode [com.tenderpulse.api.GlobalExceptionHandler] exists to catch. Complements, rather than
 * duplicates:
 * - [com.tenderpulse.domain.EntityPersistenceTest], which proves entities round-trip but never
 *   attempts a duplicate;
 * - [com.tenderpulse.subscriber.SubscriberServiceTest]'s mocked-repository tests, which prove
 *   the *app-level* pre-check (fast path, no race) throws [ConflictException] /
 *   [SubscriptionVerificationException];
 * - [com.tenderpulse.subscriber.SubscriberServiceConcurrencyTest], which races two real threads
 *   through [com.tenderpulse.subscriber.SubscriberService.registerPro] against this same
 *   constraint.
 */
@DataJpaTest
class SubscriberUniquenessConstraintTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Test
    fun `saving a second Subscriber with a duplicate email throws DataIntegrityViolationException`() {
        subscriberRepository.saveAndFlush(Subscriber(email = "dup@example.co.zw"))
        entityManager.clear()

        // saveAndFlush() (unlike a bare save() + a separately-injected TestEntityManager.flush())
        // stays entirely inside the repository proxy's own method call, so Spring's persistence
        // exception translation (PersistenceExceptionTranslationInterceptor, wired onto every
        // @Repository bean including Spring Data JPA repositories) actually gets a chance to
        // convert Hibernate's native ConstraintViolationException into this Spring-portable one —
        // exactly the type SubscriberService.register()/registerPro() (and therefore
        // GlobalExceptionHandler) see in production.
        assertThrows(DataIntegrityViolationException::class.java) {
            subscriberRepository.saveAndFlush(Subscriber(email = "dup@example.co.zw"))
        }
    }

    @Test
    fun `saving a second Subscriber with a duplicate paypalSubscriptionId throws DataIntegrityViolationException`() {
        subscriberRepository.saveAndFlush(
            Subscriber(
                email = "first-payer@example.co.zw",
                tier = SubscriptionTier.PAID,
                paypalSubscriptionId = "I-DUPLICATE-SUB-ID"
            )
        )
        entityManager.clear()

        assertThrows(DataIntegrityViolationException::class.java) {
            subscriberRepository.saveAndFlush(
                Subscriber(
                    email = "second-payer@example.co.zw",
                    tier = SubscriptionTier.PAID,
                    paypalSubscriptionId = "I-DUPLICATE-SUB-ID"
                )
            )
        }
    }

    /** Sanity check that `unique = true` still allows multiple NULLs (most subscribers are FREE). */
    @Test
    fun `two Subscribers with a null paypalSubscriptionId do not conflict`() {
        subscriberRepository.save(Subscriber(email = "free-one@example.co.zw"))
        entityManager.flush()

        subscriberRepository.save(Subscriber(email = "free-two@example.co.zw"))
        entityManager.flush()
    }
}
