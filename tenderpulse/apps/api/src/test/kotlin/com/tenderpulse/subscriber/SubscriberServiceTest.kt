package com.tenderpulse.subscriber

import com.tenderpulse.domain.ConflictException
import com.tenderpulse.domain.InterestProfile
import com.tenderpulse.domain.InterestProfileRepository
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.NotificationChannel
import com.tenderpulse.domain.PayPalApiException
import com.tenderpulse.domain.Sector
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionTier
import com.tenderpulse.domain.SubscriptionVerificationException
import com.tenderpulse.paypal.PayPalClient
import com.tenderpulse.paypal.PayPalSubscriberInfo
import com.tenderpulse.paypal.PayPalSubscriptionResponse
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
    private val payPalClient = mockk<PayPalClient>()
    private val expectedPlanId = "P-EXPECTED-PLAN"
    private val service = SubscriberService(subscriberRepository, profileRepository, payPalClient, expectedPlanId)

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

    // ---- registerPro (TP-042) ----

    private fun proRequest(
        email: String = "pro@example.com",
        subscriptionId: String = "I-VALIDSUB123"
    ) = ProSubscribeRequest(email = email, paypalSubscriptionId = subscriptionId)

    private fun paypalSubscription(
        id: String = "I-VALIDSUB123",
        status: String = "ACTIVE",
        planId: String = expectedPlanId,
        payerEmail: String? = "pro@example.com"
    ) = PayPalSubscriptionResponse(
        id = id,
        status = status,
        planId = planId,
        subscriber = payerEmail?.let { PayPalSubscriberInfo(emailAddress = it) }
    )

    /** Test case 1: valid, active, matching-plan subscription -> subscriber created as PAID. */
    @Test
    fun `registerPro creates a new PAID subscriber for a valid active matching-plan subscription`() {
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns paypalSubscription()
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALIDSUB123") } returns null
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.registerPro(proRequest())

        assertEquals("pro@example.com", result.email)
        assertEquals(SubscriptionTier.PAID, result.tier)
        assertEquals("I-VALIDSUB123", result.paypalSubscriptionId)
    }

    /** The payer email match is case-insensitive (PayPal and the request may differ in casing). */
    @Test
    fun `registerPro accepts a payer email that differs only in case from the request email`() {
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns
            paypalSubscription(payerEmail = "Pro@Example.com")
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALIDSUB123") } returns null
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.registerPro(proRequest())

        assertEquals(SubscriptionTier.PAID, result.tier)
    }

    /** Test case 1 (upgrade variant): an existing FREE subscriber is upgraded in place, not duplicated. */
    @Test
    fun `registerPro upgrades an existing FREE subscriber to PAID, preserving their id`() {
        val freeSubscriber = Subscriber(id = subscriberId, email = "pro@example.com", tier = SubscriptionTier.FREE)
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns paypalSubscription()
        every { subscriberRepository.findByEmail("pro@example.com") } returns freeSubscriber
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALIDSUB123") } returns null
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.registerPro(proRequest())

        assertEquals(subscriberId, result.id)
        assertEquals(SubscriptionTier.PAID, result.tier)
        assertEquals("I-VALIDSUB123", result.paypalSubscriptionId)
    }

    /** Test case 2: nonexistent subscription id -> rejected, no subscriber change. */
    @Test
    fun `registerPro with a nonexistent PayPal subscription id throws and saves nothing`() {
        every { payPalClient.fetchSubscription("I-FAKE") } returns null

        assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest(subscriptionId = "I-FAKE"))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /** Test case 3: subscription exists but for a different plan -> rejected, no subscriber change. */
    @Test
    fun `registerPro with a subscription for the wrong plan throws and saves nothing`() {
        every { payPalClient.fetchSubscription("I-WRONGPLAN") } returns
            paypalSubscription(id = "I-WRONGPLAN", planId = "P-SOME-OTHER-PRODUCT")

        assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest(subscriptionId = "I-WRONGPLAN"))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
        verify(exactly = 0) { subscriberRepository.findByEmail(any()) }
    }

    /** Test case 4: subscription exists but is not ACTIVE -> rejected, no subscriber change. */
    @Test
    fun `registerPro with a non-ACTIVE subscription throws and saves nothing`() {
        every { payPalClient.fetchSubscription("I-PENDING") } returns
            paypalSubscription(id = "I-PENDING", status = "APPROVAL_PENDING")

        assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest(subscriptionId = "I-PENDING"))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /** Test case 5: the call to PayPal itself fails -> propagates, no subscriber change. */
    @Test
    fun `registerPro propagates a PayPal API failure without saving anything`() {
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } throws
            PayPalApiException("PayPal timed out")

        assertThrows(PayPalApiException::class.java) {
            service.registerPro(proRequest())
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /**
     * Security fix (post-merge review of #62): an ACTIVE, matching-plan subscription that
     * belongs to a *different* PayPal payer must still be rejected — otherwise the same genuine
     * subscription could be replayed against an arbitrary victim email.
     */
    @Test
    fun `registerPro with a subscription whose PayPal payer email does not match the request email is rejected`() {
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns
            paypalSubscription(payerEmail = "someone-else@example.com")

        val ex = assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest(email = "pro@example.com"))
        }
        assertTrue(ex.message!!.contains("pro@example.com"))
        verify(exactly = 0) { subscriberRepository.save(any()) }
        verify(exactly = 0) { subscriberRepository.findByEmail(any()) }
    }

    /** A subscription with no payer email at all on PayPal's response is also rejected, not assumed to match. */
    @Test
    fun `registerPro with no payer email on the PayPal subscription is rejected`() {
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns paypalSubscription(payerEmail = null)

        assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest())
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /**
     * Security fix (post-merge review of #62): the same PayPal subscription ID cannot be used to
     * upgrade a second, different subscriber — one real payment must not mint unlimited Pro
     * accounts across different emails. (The payer-email check alone wouldn't catch this if
     * PayPal ever returned a shared/aliased payer email, so this is an independent check.)
     */
    @Test
    fun `registerPro rejects reusing a PayPal subscription id already linked to a different subscriber`() {
        val firstSubscriber = Subscriber(
            id = UUID.randomUUID(),
            email = "first@example.com",
            tier = SubscriptionTier.PAID,
            paypalSubscriptionId = "I-VALIDSUB123"
        )
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns
            paypalSubscription(payerEmail = "second@example.com")
        every { subscriberRepository.findByEmail("second@example.com") } returns null
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALIDSUB123") } returns firstSubscriber

        assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest(email = "second@example.com"))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /** The same subscriber re-submitting their own already-linked subscription id is allowed (idempotent). */
    @Test
    fun `registerPro allows the same subscriber to resubmit their own already-linked subscription id`() {
        val existingPaid = Subscriber(
            id = subscriberId,
            email = "pro@example.com",
            tier = SubscriptionTier.PAID,
            paypalSubscriptionId = "I-VALIDSUB123"
        )
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns paypalSubscription()
        every { subscriberRepository.findByEmail("pro@example.com") } returns existingPaid
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALIDSUB123") } returns existingPaid
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.registerPro(proRequest())

        assertEquals(subscriberId, result.id)
        assertEquals(SubscriptionTier.PAID, result.tier)
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
