package com.tenderpulse.domain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager
import java.math.BigDecimal

/**
 * Regression test for issue #31 (TP-031): "No default constructor for entity" 500s against a
 * real (non-mocked) JPA context.
 *
 * `@DataJpaTest` boots a real Spring context with an actual (H2) `EntityManagerFactory` and
 * Hibernate `SessionFactory` — unlike every other existing test in this module, which either
 * constructs entities directly in memory ([TenderSchemaTest]) or mocks the repository layer
 * ([com.tenderpulse.api.SubscriberControllerTest]).
 * Mocked repositories never invoke Hibernate's entity instantiator, so they could not have
 * caught (and did not catch) the missing `kotlin("plugin.jpa")` compiler plugin that left these
 * `@Entity` classes without a synthetic no-arg constructor.
 *
 * Each test below persists an entity, then calls [TestEntityManager.flush] and
 * [TestEntityManager.clear] before reading it back via the repository. `flush()` forces the
 * INSERT to hit the real database and `clear()` evicts the persistence-context cache, so the
 * subsequent read is forced through Hibernate's row -> entity instantiation path (the exact path
 * that threw `org.hibernate.InstantiationException: No default constructor for entity ...`
 * before this fix). Simply asserting on the object returned by `save()` would not exercise this
 * path, since JPA providers are free to return the same in-memory instance without re-reading.
 */
@DataJpaTest
class EntityPersistenceTest {

    @Autowired
    private lateinit var entityManager: TestEntityManager

    @Autowired
    private lateinit var subscriberRepository: SubscriberRepository

    @Autowired
    private lateinit var interestProfileRepository: InterestProfileRepository

    @Autowired
    private lateinit var tenderRepository: TenderRepository

    @Autowired
    private lateinit var notificationRecordRepository: NotificationRecordRepository

    @Autowired
    private lateinit var digestQueueEntryRepository: DigestQueueEntryRepository

    // ---- minimum bar: Subscriber (the simplest entity; WaitlistEntry, the entity that first
    //      surfaced this bug, was retired in TP-037) ----
    //
    // ---- remaining @Entity classes in Models.kt, so this class of bug cannot silently
    //      reappear for any of them ----

    @Test
    fun `Subscriber save-read round trip against a real JPA context`() {
        val saved = subscriberRepository.save(
            Subscriber(email = "subscriber-roundtrip@example.co.zw", tier = SubscriptionTier.PAID)
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = subscriberRepository.findById(saved.id).orElseThrow()

        assertEquals("subscriber-roundtrip@example.co.zw", reloaded.email)
        assertEquals(SubscriptionTier.PAID, reloaded.tier)
        assertEquals(true, reloaded.active)
    }

    /** TP-057 schema change: `emailOptOut` persists and reloads through a real JPA context. */
    @Test
    fun `Subscriber emailOptOut round trips against a real JPA context`() {
        val saved = subscriberRepository.save(
            Subscriber(email = "opted-out-roundtrip@example.co.zw", emailOptOut = true)
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = subscriberRepository.findById(saved.id).orElseThrow()

        assertEquals(true, reloaded.emailOptOut)
    }

    /** TP-042 schema change: `paypalSubscriptionId` persists and reloads through a real JPA context. */
    @Test
    fun `Subscriber paypalSubscriptionId round trips against a real JPA context`() {
        val saved = subscriberRepository.save(
            Subscriber(
                email = "pro-subscriber-roundtrip@example.co.zw",
                tier = SubscriptionTier.PAID,
                paypalSubscriptionId = "I-VALIDSUB123"
            )
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = subscriberRepository.findById(saved.id).orElseThrow()

        assertEquals("I-VALIDSUB123", reloaded.paypalSubscriptionId)
    }

    @Test
    fun `Tender save-read round trip against a real JPA context`() {
        val saved = tenderRepository.save(
            Tender(
                title = "Supply of Office Equipment",
                issuingAuthority = "Ministry of Education",
                sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR90001",
                sourceName = "egp.praz.org.zw",
                sector = Sector.EDUCATION,
                keywords = mutableSetOf("office", "equipment")
            )
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = tenderRepository.findById(saved.id).orElseThrow()

        assertEquals("Supply of Office Equipment", reloaded.title)
        assertEquals(Sector.EDUCATION, reloaded.sector)
        assertEquals(setOf("office", "equipment"), reloaded.keywords)
    }

    @Test
    fun `InterestProfile save-read round trip against a real JPA context`() {
        val subscriber = subscriberRepository.save(Subscriber(email = "profile-owner@example.co.zw"))
        entityManager.flush()

        val saved = interestProfileRepository.save(
            InterestProfile(
                subscriber = subscriber,
                sectors = mutableSetOf(Sector.IT),
                valueMin = BigDecimal("1000"),
                valueMax = BigDecimal("50000"),
                region = "Bulawayo",
                keywords = mutableSetOf("networking")
            )
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = interestProfileRepository.findById(saved.id).orElseThrow()

        assertEquals(subscriber.id, reloaded.subscriber.id)
        assertEquals(setOf(Sector.IT), reloaded.sectors)
        assertEquals("Bulawayo", reloaded.region)
        assertEquals(setOf("networking"), reloaded.keywords)
    }

    @Test
    fun `NotificationRecord save-read round trip against a real JPA context`() {
        val subscriber = subscriberRepository.save(Subscriber(email = "notif-subscriber@example.co.zw"))
        val tender = tenderRepository.save(
            Tender(
                title = "Construction of Clinic",
                issuingAuthority = "Ministry of Health",
                sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR90002",
                sourceName = "egp.praz.org.zw"
            )
        )
        entityManager.flush()

        val saved = notificationRecordRepository.save(
            NotificationRecord(
                subscriber = subscriber,
                tender = tender,
                channel = NotificationChannel.EMAIL
            )
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = notificationRecordRepository.findById(saved.id).orElseThrow()

        assertEquals(subscriber.id, reloaded.subscriber.id)
        assertEquals(tender.id, reloaded.tender.id)
        assertEquals(NotificationChannel.EMAIL, reloaded.channel)
        assertEquals(true, reloaded.success)
    }

    @Test
    fun `DigestQueueEntry save-read round trip against a real JPA context`() {
        val subscriber = subscriberRepository.save(Subscriber(email = "digest-subscriber@example.co.zw"))
        val tender = tenderRepository.save(
            Tender(
                title = "Supply of Medical Consumables",
                issuingAuthority = "Ministry of Health",
                sourceUrl = "https://egp.praz.org.zw/tenders/2026/TR90003",
                sourceName = "egp.praz.org.zw"
            )
        )
        val profile = interestProfileRepository.save(
            InterestProfile(subscriber = subscriber, sectors = mutableSetOf(Sector.HEALTHCARE))
        )
        entityManager.flush()

        val saved = digestQueueEntryRepository.save(
            DigestQueueEntry(subscriber = subscriber, tender = tender, profile = profile)
        )
        entityManager.flush()
        entityManager.clear()

        val reloaded = digestQueueEntryRepository.findById(saved.id).orElseThrow()

        assertEquals(subscriber.id, reloaded.subscriber.id)
        assertEquals(tender.id, reloaded.tender.id)
        assertEquals(profile.id, reloaded.profile.id)
        assertNotNull(reloaded.queuedAt)
    }
}
