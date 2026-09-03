package com.tenderpulse.subscriber

import com.tenderpulse.domain.ConflictException
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.NotificationChannel
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for [SubscriberService] (TP-037). Repositories are mockk mocks; this class is the
 * home for the business-logic assertions that used to live in
 * `com.tenderpulse.api.SubscriberControllerTest` before that controller was made thin.
 */
class SubscriberServiceTest {

    private val subscriberRepository = mockk<SubscriberRepository>()
    private val profileRepository = mockk<InterestProfileRepository>()
    private val service = SubscriberService(subscriberRepository, profileRepository)

    private val subscriberId: UUID = UUID.randomUUID()
    private val subscriber = Subscriber(id = subscriberId, email = "sub@example.com")

    // ---- register ----

    @Test
    fun `register saves a new subscriber with the requested tier`() {
        every { subscriberRepository.findByEmail("new@example.com") } returns null
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.register(RegisterRequest(email = "new@example.com", tier = SubscriptionTier.PAID))

        assertEquals("new@example.com", result.email)
        assertEquals(SubscriptionTier.PAID, result.tier)
    }

    @Test
    fun `register defaults to FREE tier when none requested`() {
        every { subscriberRepository.findByEmail("new@example.com") } returns null
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.register(RegisterRequest(email = "new@example.com"))

        assertEquals(SubscriptionTier.FREE, result.tier)
    }

    @Test
    fun `register with an already-registered email throws ConflictException`() {
        every { subscriberRepository.findByEmail("sub@example.com") } returns subscriber

        assertThrows(ConflictException::class.java) {
            service.register(RegisterRequest(email = "sub@example.com"))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    // ---- createProfile ----

    @Test
    fun `createProfile persists what was requested for an existing subscriber`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val saved = slot<InterestProfile>()
        every { profileRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.createProfile(
            subscriberId,
            ProfileRequest(
                sectors = setOf(Sector.IT),
                valueMin = BigDecimal("100000"),
                valueMax = BigDecimal("500000"),
                region = "Harare"
            )
        )

        assertEquals(setOf(Sector.IT), result.sectors)
        assertEquals(BigDecimal("100000"), result.valueMin)
        assertEquals(BigDecimal("500000"), result.valueMax)
        assertEquals("Harare", result.region)
        assertEquals(subscriberId, result.subscriber.id)
        assertTrue(result.active)
    }

    @Test
    fun `createProfile for unknown subscriber throws NotFoundException`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) {
            service.createProfile(subscriberId, ProfileRequest())
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    // ---- listProfiles ----

    @Test
    fun `listProfiles returns all profiles for the subscriber including inactive ones`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val activeProfile = InterestProfile(subscriber = subscriber, active = true)
        val inactiveProfile = InterestProfile(subscriber = subscriber, active = false)
        every { profileRepository.findBySubscriberId(subscriberId) } returns listOf(activeProfile, inactiveProfile)

        val result = service.listProfiles(subscriberId)

        assertEquals(listOf(activeProfile, inactiveProfile), result)
    }

    @Test
    fun `listProfiles for unknown subscriber throws NotFoundException`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) { service.listProfiles(subscriberId) }
    }

    // ---- updateProfile ----

    @Test
    fun `updateProfile mutates the intended fields and persists`() {
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(
            id = profileId,
            subscriber = subscriber,
            sectors = mutableSetOf(Sector.IT),
            region = "Harare"
        )
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)
        val saved = slot<InterestProfile>()
        every { profileRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.updateProfile(
            subscriberId,
            profileId,
            ProfileRequest(sectors = setOf(Sector.HEALTHCARE), region = "Bulawayo")
        )

        assertEquals(profileId, result.id)
        assertEquals(setOf(Sector.HEALTHCARE), result.sectors)
        assertEquals("Bulawayo", result.region)
    }

    @Test
    fun `updateProfile can deactivate a profile`() {
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(id = profileId, subscriber = subscriber, active = true)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)
        every { profileRepository.save(any()) } answers { firstArg() }

        val result = service.updateProfile(subscriberId, profileId, ProfileRequest(active = false))

        assertFalse(result.active)
    }

    @Test
    fun `updateProfile for unknown subscriber throws NotFoundException`() {
        val profileId = UUID.randomUUID()
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) {
            service.updateProfile(subscriberId, profileId, ProfileRequest())
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `updateProfile for unknown profile throws NotFoundException`() {
        val profileId = UUID.randomUUID()
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) {
            service.updateProfile(subscriberId, profileId, ProfileRequest())
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `updateProfile for a profile belonging to a different subscriber throws NotFoundException`() {
        val otherSubscriber = Subscriber(id = UUID.randomUUID(), email = "other@example.com")
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(id = profileId, subscriber = otherSubscriber)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)

        assertThrows(NotFoundException::class.java) {
            service.updateProfile(subscriberId, profileId, ProfileRequest())
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `updateProfile with an empty preferredChannels request defaults to EMAIL`() {
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(id = profileId, subscriber = subscriber)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)
        every { profileRepository.save(any()) } answers { firstArg() }

        val result = service.updateProfile(
            subscriberId,
            profileId,
            ProfileRequest(preferredChannels = emptySet())
        )

        assertEquals(setOf(NotificationChannel.EMAIL), result.preferredChannels)
    }
}
