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
import com.tenderpulse.domain.SubscriptionPlan
import com.tenderpulse.domain.SubscriptionVerificationException
import com.tenderpulse.domain.TierRestrictionException
import com.tenderpulse.paypal.PayPalClient
import com.tenderpulse.paypal.PayPalSubscriberInfo
import com.tenderpulse.paypal.PayPalSubscriptionResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
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

    /**
     * Security fix (issue #123): `register()` is the public, unauthenticated signup path and must
     * never trust a client-supplied `tier` -- there is no PayPal verification or admin gate on
     * this endpoint to justify granting anything above `FREE`. This test previously asserted the
     * opposite (that a client-requested `PRO` tier was honored verbatim), which was the exact
     * vulnerability reported in #123: `POST /api/v1/subscribers {"tier": "MAX"}` minted a full
     * MAX-tier subscriber for free. Correcting this test to assert the fixed behavior -- rather
     * than leaving it encoding the vulnerability -- is the AC's explicit requirement, not a
     * weakening of coverage.
     */
    @Test
    fun `register ignores a client-supplied tier and always creates a FREE subscriber`() {
        every { subscriberRepository.findByEmail("new@example.com") } returns null
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.register(RegisterRequest(email = "new@example.com", tier = SubscriptionPlan.PRO))

        assertEquals("new@example.com", result.email)
        assertEquals(SubscriptionPlan.FREE, result.tier)
    }

    @Test
    fun `register defaults to FREE tier when none requested`() {
        every { subscriberRepository.findByEmail("new@example.com") } returns null
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.register(RegisterRequest(email = "new@example.com"))

        assertEquals(SubscriptionPlan.FREE, result.tier)
    }

    /**
     * Issue #123, test case 2 (the actual regression-proof case): a client claiming the highest
     * tier via the public signup endpoint must still only ever get FREE.
     */
    @Test
    fun `register creates a FREE subscriber even when the client requests MAX tier`() {
        every { subscriberRepository.findByEmail("attacker@example.com") } returns null
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.register(RegisterRequest(email = "attacker@example.com", tier = SubscriptionPlan.MAX))

        assertEquals(SubscriptionPlan.FREE, result.tier)
        assertEquals(SubscriptionPlan.FREE, saved.captured.tier)
    }

    /** Issue #123, test case 3: same self-escalation attempt, but for PRO instead of MAX. */
    @Test
    fun `register creates a FREE subscriber even when the client requests PRO tier`() {
        every { subscriberRepository.findByEmail("attacker2@example.com") } returns null
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.register(RegisterRequest(email = "attacker2@example.com", tier = SubscriptionPlan.PRO))

        assertEquals(SubscriptionPlan.FREE, result.tier)
        assertEquals(SubscriptionPlan.FREE, saved.captured.tier)
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

    /** Test case 1: valid, active, matching-plan subscription -> subscriber created as PRO. */
    @Test
    fun `registerPro creates a new PRO subscriber for a valid active matching-plan subscription`() {
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns paypalSubscription()
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALIDSUB123") } returns null
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.registerPro(proRequest())

        assertEquals("pro@example.com", result.email)
        assertEquals(SubscriptionPlan.PRO, result.tier)
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

        assertEquals(SubscriptionPlan.PRO, result.tier)
    }

    /** Test case 1 (upgrade variant): an existing FREE subscriber is upgraded in place, not duplicated. */
    @Test
    fun `registerPro upgrades an existing FREE subscriber to PRO, preserving their id`() {
        val freeSubscriber = Subscriber(id = subscriberId, email = "pro@example.com", tier = SubscriptionPlan.FREE)
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns paypalSubscription()
        every { subscriberRepository.findByEmail("pro@example.com") } returns freeSubscriber
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALIDSUB123") } returns null
        val saved = slot<Subscriber>()
        every { subscriberRepository.save(capture(saved)) } answers { saved.captured }

        val result = service.registerPro(proRequest())

        assertEquals(subscriberId, result.id)
        assertEquals(SubscriptionPlan.PRO, result.tier)
        assertEquals("I-VALIDSUB123", result.paypalSubscriptionId)
    }

    /** Test case 2: nonexistent subscription id -> rejected, no subscriber change. */
    @Test
    fun `registerPro with a nonexistent PayPal subscription id throws and saves nothing`() {
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
        every { payPalClient.fetchSubscription("I-FAKE") } returns null

        assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest(subscriptionId = "I-FAKE"))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /** Test case 3: subscription exists but for a different plan -> rejected, no subscriber change. */
    @Test
    fun `registerPro with a subscription for the wrong plan throws and saves nothing`() {
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
        every { payPalClient.fetchSubscription("I-WRONGPLAN") } returns
            paypalSubscription(id = "I-WRONGPLAN", planId = "P-SOME-OTHER-PRODUCT")

        assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest(subscriptionId = "I-WRONGPLAN"))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /** Test case 4: subscription exists but is not ACTIVE -> rejected, no subscriber change. */
    @Test
    fun `registerPro with a non-ACTIVE subscription throws and saves nothing`() {
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
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
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
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
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns
            paypalSubscription(payerEmail = "someone-else@example.com")

        val ex = assertThrows(SubscriptionVerificationException::class.java) {
            service.registerPro(proRequest(email = "pro@example.com"))
        }
        assertTrue(ex.message!!.contains("pro@example.com"))
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /** A subscription with no payer email at all on PayPal's response is also rejected, not assumed to match. */
    @Test
    fun `registerPro with no payer email on the PayPal subscription is rejected`() {
        every { subscriberRepository.findByEmail("pro@example.com") } returns null
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
            tier = SubscriptionPlan.PRO,
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
            tier = SubscriptionPlan.PRO,
            paypalSubscriptionId = "I-VALIDSUB123"
        )
        every { payPalClient.fetchSubscription("I-VALIDSUB123") } returns paypalSubscription()
        every { subscriberRepository.findByEmail("pro@example.com") } returns existingPaid
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALIDSUB123") } returns existingPaid
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.registerPro(proRequest())

        assertEquals(subscriberId, result.id)
        assertEquals(SubscriptionPlan.PRO, result.tier)
    }

    // ---- registerPro plan-change guard (TP-130 follow-up, issue #132) ----

    /**
     * Issue #132, test case 2: an existing PAID (non-FREE) subscriber re-calling this endpoint
     * with a NEW, different PayPal subscription id must be rejected up front -- before PayPal is
     * ever called or anything is persisted -- rather than silently re-linking their email to the
     * new subscription and orphaning the original, still-active one with no cancellation. Mirrors
     * [com.tenderpulse.billing.BillingServiceTest]'s equivalent guard test for
     * `BillingService.confirmSubscription` (TP-130, issue #130).
     */
    @Test
    fun `registerPro rejects an existing PAID subscriber re-calling with a new different PayPal subscription id`() {
        val existingPaid = Subscriber(
            id = subscriberId,
            email = "pro@example.com",
            tier = SubscriptionPlan.PRO,
            paypalSubscriptionId = "I-ORIGINAL"
        )
        every { subscriberRepository.findByEmail("pro@example.com") } returns existingPaid

        val ex = assertThrows(ConflictException::class.java) {
            service.registerPro(proRequest(email = "pro@example.com", subscriptionId = "I-NEW-DIFFERENT"))
        }

        assertTrue(ex.message!!.contains("I-ORIGINAL"))
        verify(exactly = 0) { payPalClient.fetchSubscription(any()) }
        verify(exactly = 0) { subscriberRepository.save(any()) }
        // The original subscriber record is untouched: it is never re-fetched by id or altered.
        assertEquals("I-ORIGINAL", existingPaid.paypalSubscriptionId)
        assertEquals(SubscriptionPlan.PRO, existingPaid.tier)
    }

    /**
     * Issue #132, test case 2 (MAX variant): the same guard applies to an existing MAX
     * subscriber, not just PRO -- the check is "non-FREE", not "not PRO".
     */
    @Test
    fun `registerPro rejects an existing MAX subscriber re-calling with a new different PayPal subscription id`() {
        val existingMax = Subscriber(
            id = subscriberId,
            email = "max@example.com",
            tier = SubscriptionPlan.MAX,
            paypalSubscriptionId = "I-ORIGINAL-MAX"
        )
        every { subscriberRepository.findByEmail("max@example.com") } returns existingMax

        assertThrows(ConflictException::class.java) {
            service.registerPro(proRequest(email = "max@example.com", subscriptionId = "I-NEW-DIFFERENT"))
        }
        verify(exactly = 0) { payPalClient.fetchSubscription(any()) }
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
                name = "Harare IT Profile",
                sectors = setOf(Sector.IT),
                valueMin = BigDecimal("100000"),
                valueMax = BigDecimal("500000"),
                region = "Harare"
            )
        )

        assertEquals("Harare IT Profile", result.name)
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
            service.createProfile(subscriberId, ProfileRequest(name = "Test Profile"))
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    /**
     * Issue #58, test case 1: a subscriber can create a second, differently-named profile —
     * there is no artificial limit of one profile per subscriber.
     */
    @Test
    fun `createProfile allows a second, differently-named profile for the same subscriber`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val savedProfiles = mutableListOf<InterestProfile>()
        every { profileRepository.save(any()) } answers {
            (firstArg() as InterestProfile).also { savedProfiles.add(it) }
        }

        val first = service.createProfile(subscriberId, ProfileRequest(name = "Construction Tenders"))
        val second = service.createProfile(subscriberId, ProfileRequest(name = "IT Tenders"))

        assertEquals("Construction Tenders", first.name)
        assertEquals("IT Tenders", second.name)
        assertNotEquals(first.id, second.id)
        assertEquals(subscriberId, first.subscriber.id)
        assertEquals(subscriberId, second.subscriber.id)
        verify(exactly = 2) { profileRepository.save(any()) }
        assertEquals(2, savedProfiles.size)
    }

    // ---- listProfiles ----

    @Test
    fun `listProfiles returns all profiles for the subscriber including inactive ones`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val activeProfile = InterestProfile(subscriber = subscriber, name = "Active Profile", active = true)
        val inactiveProfile = InterestProfile(subscriber = subscriber, name = "Inactive Profile", active = false)
        every { profileRepository.findBySubscriberId(subscriberId) } returns listOf(activeProfile, inactiveProfile)

        val result = service.listProfiles(subscriberId)

        assertEquals(listOf(activeProfile, inactiveProfile), result)
    }

    /** Issue #58, test case 4: listing a subscriber's 2 profiles returns both, with their names. */
    @Test
    fun `listProfiles returns two profiles for the same subscriber each with their own name`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        val profileA = InterestProfile(subscriber = subscriber, name = "Construction Tenders")
        val profileB = InterestProfile(subscriber = subscriber, name = "IT Tenders")
        every { profileRepository.findBySubscriberId(subscriberId) } returns listOf(profileA, profileB)

        val result = service.listProfiles(subscriberId)

        assertEquals(2, result.size)
        assertEquals(setOf("Construction Tenders", "IT Tenders"), result.map { it.name }.toSet())
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
            name = "Old Name",
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
            ProfileRequest(name = "New Name", sectors = setOf(Sector.HEALTHCARE), region = "Bulawayo")
        )

        assertEquals(profileId, result.id)
        assertEquals("New Name", result.name)
        assertEquals(setOf(Sector.HEALTHCARE), result.sectors)
        assertEquals("Bulawayo", result.region)
    }

    @Test
    fun `updateProfile can deactivate a profile`() {
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(id = profileId, subscriber = subscriber, name = "Test Profile", active = true)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)
        every { profileRepository.save(any()) } answers { firstArg() }

        val result = service.updateProfile(subscriberId, profileId, ProfileRequest(name = "Test Profile", active = false))

        assertFalse(result.active)
    }

    @Test
    fun `updateProfile for unknown subscriber throws NotFoundException`() {
        val profileId = UUID.randomUUID()
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) {
            service.updateProfile(subscriberId, profileId, ProfileRequest(name = "Test Profile"))
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `updateProfile for unknown profile throws NotFoundException`() {
        val profileId = UUID.randomUUID()
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) {
            service.updateProfile(subscriberId, profileId, ProfileRequest(name = "Test Profile"))
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `updateProfile for a profile belonging to a different subscriber throws NotFoundException`() {
        val otherSubscriber = Subscriber(id = UUID.randomUUID(), email = "other@example.com")
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(id = profileId, subscriber = otherSubscriber, name = "Test Profile")
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)

        assertThrows(NotFoundException::class.java) {
            service.updateProfile(subscriberId, profileId, ProfileRequest(name = "Test Profile"))
        }
        verify(exactly = 0) { profileRepository.save(any()) }
    }

    @Test
    fun `updateProfile with an empty preferredChannels request defaults to EMAIL`() {
        val profileId = UUID.randomUUID()
        val existing = InterestProfile(id = profileId, subscriber = subscriber, name = "Test Profile")
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(subscriber)
        every { profileRepository.findById(profileId) } returns Optional.of(existing)
        every { profileRepository.save(any()) } answers { firstArg() }

        val result = service.updateProfile(
            subscriberId,
            profileId,
            ProfileRequest(name = "Test Profile", preferredChannels = emptySet())
        )

        assertEquals(setOf(NotificationChannel.EMAIL), result.preferredChannels)
    }

    // ---- setWhatsAppOptIn (TP-093) ----

    /** Test case 1: a Paid subscriber's valid number + explicit consent stores both fields. */
    @Test
    fun `setWhatsAppOptIn stores the number and true optIn for a Paid subscriber with explicit consent`() {
        val paidSubscriber = subscriber.copy(tier = SubscriptionPlan.PRO)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(paidSubscriber)
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.setWhatsAppOptIn(
            subscriberId,
            WhatsAppOptInRequest(number = "+263771234567", consentGiven = true)
        )

        assertEquals("+263771234567", result.whatsappNumber)
        assertTrue(result.whatsappOptIn)
    }

    /** Test case 2: consent omitted (defaults false) stores the number but never sets optIn true. */
    @Test
    fun `setWhatsAppOptIn stores the number but leaves optIn false when consentGiven is not given`() {
        val paidSubscriber = subscriber.copy(tier = SubscriptionPlan.PRO)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(paidSubscriber)
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.setWhatsAppOptIn(subscriberId, WhatsAppOptInRequest(number = "+263771234567"))

        assertEquals("+263771234567", result.whatsappNumber)
        assertFalse(result.whatsappOptIn)
    }

    /**
     * Resubmitting with `consentGiven: false` after a prior `true` genuinely revokes opt-in --
     * guards against an implementation that coalesces with the subscriber's existing stored value
     * instead of setting it to exactly what was submitted this time.
     */
    @Test
    fun `setWhatsAppOptIn revokes a previously-true optIn when consentGiven is now false`() {
        val previouslyOptedIn = subscriber.copy(
            tier = SubscriptionPlan.PRO,
            whatsappNumber = "+263771234567",
            whatsappOptIn = true
        )
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(previouslyOptedIn)
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.setWhatsAppOptIn(
            subscriberId,
            WhatsAppOptInRequest(number = "+263771234567", consentGiven = false)
        )

        assertFalse(result.whatsappOptIn)
    }

    /** Test case 4: a Free-tier subscriber is rejected, no save attempted. */
    @Test
    fun `setWhatsAppOptIn rejects a Free-tier subscriber and saves nothing`() {
        val freeSubscriber = subscriber.copy(tier = SubscriptionPlan.FREE)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(freeSubscriber)

        assertThrows(TierRestrictionException::class.java) {
            service.setWhatsAppOptIn(subscriberId, WhatsAppOptInRequest(number = "+263771234567", consentGiven = true))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /**
     * TP-121 (issue #121): MAX must behave identically to PRO here -- no MAX-exclusive behavior
     * yet, and MAX must not be mistakenly caught by a check written as "not PRO".
     */
    @Test
    fun `setWhatsAppOptIn stores the number and true optIn for a MAX subscriber, identically to PRO`() {
        val maxSubscriber = subscriber.copy(tier = SubscriptionPlan.MAX)
        every { subscriberRepository.findById(subscriberId) } returns Optional.of(maxSubscriber)
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service.setWhatsAppOptIn(
            subscriberId,
            WhatsAppOptInRequest(number = "+263771234567", consentGiven = true)
        )

        assertEquals("+263771234567", result.whatsappNumber)
        assertTrue(result.whatsappOptIn)
    }

    @Test
    fun `setWhatsAppOptIn for an unknown subscriber throws NotFoundException`() {
        every { subscriberRepository.findById(subscriberId) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) {
            service.setWhatsAppOptIn(subscriberId, WhatsAppOptInRequest(number = "+263771234567", consentGiven = true))
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }
}
