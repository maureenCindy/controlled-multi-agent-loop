package com.tenderpulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager

/**
 * Repository-level coverage for [InterestProfileRepository.findAllActiveWithSubscriber] against a
 * real (H2) JPA context — unlike [com.tenderpulse.notification.NotificationServiceTest], which
 * mocks this repository and therefore can't exercise the actual query.
 *
 * TP-057 (issue #57): an opted-out subscriber ([Subscriber.emailOptOut]) must be excluded from
 * matching/notification cycles going forward, on top of the pre-existing active-profile /
 * active-subscriber filters.
 */
@DataJpaTest
class InterestProfileRepositoryTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var profileRepository: InterestProfileRepository

    @Test
    fun `an opted-out subscriber's active profile is excluded`() {
        val optedOut = subscriberRepository.save(
            Subscriber(email = "opted-out@example.co.zw", emailOptOut = true)
        )
        val stillIn = subscriberRepository.save(
            Subscriber(email = "still-subscribed@example.co.zw", emailOptOut = false)
        )
        profileRepository.save(InterestProfile(subscriber = optedOut, name = "Opted Out Profile", active = true))
        val keptProfile = profileRepository.save(InterestProfile(subscriber = stillIn, name = "Kept Profile", active = true))
        entityManager.flush()
        entityManager.clear()

        val results = profileRepository.findAllActiveWithSubscriber()

        assertEquals(1, results.size)
        assertEquals(keptProfile.id, results.single().id)
        assertTrue(results.none { it.subscriber.emailOptOut })
    }

    @Test
    fun `an active, not-opted-out subscriber's active profile is still returned`() {
        val subscriber = subscriberRepository.save(Subscriber(email = "regular@example.co.zw"))
        val profile = profileRepository.save(InterestProfile(subscriber = subscriber, name = "Regular Profile", active = true))
        entityManager.flush()
        entityManager.clear()

        val results = profileRepository.findAllActiveWithSubscriber()

        assertTrue(results.any { it.id == profile.id })
    }
}
