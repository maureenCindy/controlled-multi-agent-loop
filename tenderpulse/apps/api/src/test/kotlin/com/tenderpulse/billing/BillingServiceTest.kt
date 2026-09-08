package com.tenderpulse.billing

import com.tenderpulse.domain.ConflictException
import com.tenderpulse.domain.NotFoundException
import com.tenderpulse.domain.Subscriber
import com.tenderpulse.domain.SubscriberRepository
import com.tenderpulse.domain.SubscriptionPlan
import com.tenderpulse.domain.SubscriptionVerificationException
import com.tenderpulse.paypal.PayPalClient
import com.tenderpulse.paypal.PayPalSubscriptionResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

/**
 * Unit tests for [BillingService] (TP-127, issue #127) -- covers all 6 issue test cases at the
 * service layer (integration-level, full-security-chain coverage for the same scenarios lives in
 * `BillingControllerIntegrationTest`).
 */
class BillingServiceTest {

    private val subscriberRepository = mockk<SubscriberRepository>()
    private val payPalClient = mockk<PayPalClient>()

    private fun service(proPlanId: String = "P-PRO-CONFIGURED", maxPlanId: String = "P-MAX-CONFIGURED") =
        BillingService(
            subscriberRepository = subscriberRepository,
            payPalClient = payPalClient,
            publicClientId = "public-client-id",
            currency = "USD",
            proPlanId = proPlanId,
            maxPlanId = maxPlanId,
            proAmount = "5.00",
            maxAmount = "30.00"
        )

    private fun subscriber(
        id: UUID = UUID.randomUUID(),
        tier: SubscriptionPlan = SubscriptionPlan.FREE,
        paypalSubscriptionId: String? = null
    ) = Subscriber(id = id, email = "subscriber-$id@example.com", tier = tier, paypalSubscriptionId = paypalSubscriptionId)

    // ---- publicConfig ----

    @Test
    fun `publicConfig returns the configured provider, clientId, currency, and both plans' id and amount`() {
        val response = service().publicConfig()

        assertEquals("PAYPAL", response.provider)
        assertEquals("public-client-id", response.clientId)
        assertEquals("USD", response.currency)
        assertEquals(PlanConfig(paypalPlanId = "P-PRO-CONFIGURED", amount = "5.00"), response.plans["PRO"])
        assertEquals(PlanConfig(paypalPlanId = "P-MAX-CONFIGURED", amount = "30.00"), response.plans["MAX"])
    }

    // ---- Test case 1: valid, ACTIVE, matching-plan confirm upgrades the subscriber ----

    @Test
    fun `confirmSubscription upgrades to PRO for a valid ACTIVE subscription matching the configured Pro plan`() {
        val sub = subscriber()
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { subscriberRepository.findByPaypalSubscriptionId("I-VALID") } returns null
        every { payPalClient.fetchSubscription("I-VALID") } returns
            PayPalSubscriptionResponse(id = "I-VALID", status = "ACTIVE", planId = "P-PRO-CONFIGURED")
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service().confirmSubscription(
            sub.id,
            ConfirmSubscriptionRequest(paypalSubscriptionId = "I-VALID", requestedPlan = RequestedBillingPlan.PRO)
        )

        assertEquals(SubscriptionPlan.PRO, result.tier)
        assertEquals("I-VALID", result.paypalSubscriptionId)
        assertEquals(sub.id, result.id)
    }

    @Test
    fun `confirmSubscription upgrades to MAX for a valid ACTIVE subscription matching the configured Max plan`() {
        val sub = subscriber()
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { subscriberRepository.findByPaypalSubscriptionId("I-MAX-VALID") } returns null
        every { payPalClient.fetchSubscription("I-MAX-VALID") } returns
            PayPalSubscriptionResponse(id = "I-MAX-VALID", status = "ACTIVE", planId = "P-MAX-CONFIGURED")
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service().confirmSubscription(
            sub.id,
            ConfirmSubscriptionRequest(paypalSubscriptionId = "I-MAX-VALID", requestedPlan = RequestedBillingPlan.MAX)
        )

        assertEquals(SubscriptionPlan.MAX, result.tier)
        assertEquals("I-MAX-VALID", result.paypalSubscriptionId)
    }

    // ---- Test case 2: plan mismatch is rejected ----

    @Test
    fun `confirmSubscription rejects a subscription whose plan id does not match the requested plan's configured id`() {
        val sub = subscriber()
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { payPalClient.fetchSubscription("I-WRONG-PLAN") } returns
            PayPalSubscriptionResponse(id = "I-WRONG-PLAN", status = "ACTIVE", planId = "P-SOME-OTHER-PLAN")

        assertThrows(SubscriptionVerificationException::class.java) {
            service().confirmSubscription(
                sub.id,
                ConfirmSubscriptionRequest(paypalSubscriptionId = "I-WRONG-PLAN", requestedPlan = RequestedBillingPlan.PRO)
            )
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    @Test
    fun `confirmSubscription rejects a subscription that matches the Pro plan id when Max was requested`() {
        val sub = subscriber()
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { payPalClient.fetchSubscription("I-PRO-NOT-MAX") } returns
            PayPalSubscriptionResponse(id = "I-PRO-NOT-MAX", status = "ACTIVE", planId = "P-PRO-CONFIGURED")

        assertThrows(SubscriptionVerificationException::class.java) {
            service().confirmSubscription(
                sub.id,
                ConfirmSubscriptionRequest(paypalSubscriptionId = "I-PRO-NOT-MAX", requestedPlan = RequestedBillingPlan.MAX)
            )
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    // ---- Test case 3: non-ACTIVE subscription is rejected ----

    @Test
    fun `confirmSubscription rejects a non-ACTIVE subscription even when the plan id matches`() {
        val sub = subscriber()
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { payPalClient.fetchSubscription("I-SUSPENDED") } returns
            PayPalSubscriptionResponse(id = "I-SUSPENDED", status = "SUSPENDED", planId = "P-PRO-CONFIGURED")

        val ex = assertThrows(SubscriptionVerificationException::class.java) {
            service().confirmSubscription(
                sub.id,
                ConfirmSubscriptionRequest(paypalSubscriptionId = "I-SUSPENDED", requestedPlan = RequestedBillingPlan.PRO)
            )
        }
        assertTrue(ex.message!!.contains("SUSPENDED"))
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    @Test
    fun `confirmSubscription rejects an unknown PayPal subscription id`() {
        val sub = subscriber()
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { payPalClient.fetchSubscription("I-DOES-NOT-EXIST") } returns null

        assertThrows(SubscriptionVerificationException::class.java) {
            service().confirmSubscription(
                sub.id,
                ConfirmSubscriptionRequest(paypalSubscriptionId = "I-DOES-NOT-EXIST", requestedPlan = RequestedBillingPlan.PRO)
            )
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    // ---- Test case 4: duplicate subscription-id linking is rejected ----

    @Test
    fun `confirmSubscription rejects a subscription id already linked to a different subscriber`() {
        val sub = subscriber()
        val otherSubscriber = subscriber(paypalSubscriptionId = "I-ALREADY-LINKED")
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { payPalClient.fetchSubscription("I-ALREADY-LINKED") } returns
            PayPalSubscriptionResponse(id = "I-ALREADY-LINKED", status = "ACTIVE", planId = "P-PRO-CONFIGURED")
        every { subscriberRepository.findByPaypalSubscriptionId("I-ALREADY-LINKED") } returns otherSubscriber

        assertThrows(SubscriptionVerificationException::class.java) {
            service().confirmSubscription(
                sub.id,
                ConfirmSubscriptionRequest(paypalSubscriptionId = "I-ALREADY-LINKED", requestedPlan = RequestedBillingPlan.PRO)
            )
        }
        verify(exactly = 0) { subscriberRepository.save(any()) }
    }

    /**
     * Idempotency: a retry with the same subscription id, by the *same* already-linked
     * subscriber, must succeed (not be rejected as a duplicate) and must not double-process --
     * the save simply re-persists the same plan/subscription-id pair.
     */
    @Test
    fun `confirmSubscription is idempotent for a retry by the subscriber already linked to that subscription id`() {
        val sub = subscriber(tier = SubscriptionPlan.PRO, paypalSubscriptionId = "I-RETRY")
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { payPalClient.fetchSubscription("I-RETRY") } returns
            PayPalSubscriptionResponse(id = "I-RETRY", status = "ACTIVE", planId = "P-PRO-CONFIGURED")
        every { subscriberRepository.findByPaypalSubscriptionId("I-RETRY") } returns sub
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service().confirmSubscription(
            sub.id,
            ConfirmSubscriptionRequest(paypalSubscriptionId = "I-RETRY", requestedPlan = RequestedBillingPlan.PRO)
        )

        assertEquals(SubscriptionPlan.PRO, result.tier)
        assertEquals("I-RETRY", result.paypalSubscriptionId)
        verify(exactly = 1) { subscriberRepository.save(any()) }
    }

    // ---- Ownership: the target is always the authenticated caller, not anything in the request body ----

    @Test
    fun `confirmSubscription throws NotFoundException if the authenticated subscriber id no longer exists`() {
        val missingId = UUID.randomUUID()
        every { subscriberRepository.findById(missingId) } returns Optional.empty()

        assertThrows(NotFoundException::class.java) {
            service().confirmSubscription(
                missingId,
                ConfirmSubscriptionRequest(paypalSubscriptionId = "I-VALID", requestedPlan = RequestedBillingPlan.PRO)
            )
        }
    }

    // ---- Issue #130: guard against silent plan-change double-billing ----

    /**
     * Issue test case 1: a FREE subscriber (no existing PayPal subscription) confirming a new
     * subscription is unaffected by the new guard -- explicit regression coverage for the exact
     * scenario the issue calls out, on top of the pre-existing "upgrades to PRO/MAX" tests above.
     */
    @Test
    fun `issue 130 case 1 - a FREE subscriber with no existing subscription can still confirm normally`() {
        val sub = subscriber(tier = SubscriptionPlan.FREE, paypalSubscriptionId = null)
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { subscriberRepository.findByPaypalSubscriptionId("I-FRESH") } returns null
        every { payPalClient.fetchSubscription("I-FRESH") } returns
            PayPalSubscriptionResponse(id = "I-FRESH", status = "ACTIVE", planId = "P-PRO-CONFIGURED")
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service().confirmSubscription(
            sub.id,
            ConfirmSubscriptionRequest(paypalSubscriptionId = "I-FRESH", requestedPlan = RequestedBillingPlan.PRO)
        )

        assertEquals(SubscriptionPlan.PRO, result.tier)
        assertEquals("I-FRESH", result.paypalSubscriptionId)
    }

    /** Issue test case 2: existing PRO subscriber attempts to confirm a new, different MAX subscription. */
    @Test
    fun `issue 130 case 2 - an existing PRO subscriber confirming a different MAX subscription is rejected, original untouched`() {
        val sub = subscriber(tier = SubscriptionPlan.PRO, paypalSubscriptionId = "I-EXISTING-PRO")
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)

        val ex = assertThrows(ConflictException::class.java) {
            service().confirmSubscription(
                sub.id,
                ConfirmSubscriptionRequest(paypalSubscriptionId = "I-NEW-MAX", requestedPlan = RequestedBillingPlan.MAX)
            )
        }
        assertTrue(ex.message!!.contains("I-EXISTING-PRO"))
        assertTrue(ex.message!!.contains("PRO"))

        // Rejected before ever calling out to PayPal or touching the repository.
        verify(exactly = 0) { payPalClient.fetchSubscription(any()) }
        verify(exactly = 0) { subscriberRepository.save(any()) }
        assertEquals(SubscriptionPlan.PRO, sub.tier)
        assertEquals("I-EXISTING-PRO", sub.paypalSubscriptionId)
    }

    /** Issue test case 3: existing MAX subscriber attempts to confirm a new, different PRO subscription. */
    @Test
    fun `issue 130 case 3 - an existing MAX subscriber confirming a different PRO subscription is rejected, original untouched`() {
        val sub = subscriber(tier = SubscriptionPlan.MAX, paypalSubscriptionId = "I-EXISTING-MAX")
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)

        val ex = assertThrows(ConflictException::class.java) {
            service().confirmSubscription(
                sub.id,
                ConfirmSubscriptionRequest(paypalSubscriptionId = "I-NEW-PRO", requestedPlan = RequestedBillingPlan.PRO)
            )
        }
        assertTrue(ex.message!!.contains("I-EXISTING-MAX"))
        assertTrue(ex.message!!.contains("MAX"))

        verify(exactly = 0) { payPalClient.fetchSubscription(any()) }
        verify(exactly = 0) { subscriberRepository.save(any()) }
        assertEquals(SubscriptionPlan.MAX, sub.tier)
        assertEquals("I-EXISTING-MAX", sub.paypalSubscriptionId)
    }

    /**
     * The guard must not break the pre-existing idempotent-retry behavior: a retry with the *same*
     * subscription id by the same already-linked subscriber is exempted (already covered by
     * `confirmSubscription is idempotent for a retry...` above); this test pins down that a
     * *different* new subscription id from a non-FREE subscriber is what triggers the guard, not
     * merely having an existing tier/subscription id.
     */
    @Test
    fun `issue 130 - a non-FREE subscriber retrying with the same subscription id is not blocked by the guard`() {
        val sub = subscriber(tier = SubscriptionPlan.PRO, paypalSubscriptionId = "I-SAME")
        every { subscriberRepository.findById(sub.id) } returns Optional.of(sub)
        every { subscriberRepository.findByPaypalSubscriptionId("I-SAME") } returns sub
        every { payPalClient.fetchSubscription("I-SAME") } returns
            PayPalSubscriptionResponse(id = "I-SAME", status = "ACTIVE", planId = "P-PRO-CONFIGURED")
        every { subscriberRepository.save(any()) } answers { firstArg() }

        val result = service().confirmSubscription(
            sub.id,
            ConfirmSubscriptionRequest(paypalSubscriptionId = "I-SAME", requestedPlan = RequestedBillingPlan.PRO)
        )

        assertEquals(SubscriptionPlan.PRO, result.tier)
        assertEquals("I-SAME", result.paypalSubscriptionId)
    }

}
